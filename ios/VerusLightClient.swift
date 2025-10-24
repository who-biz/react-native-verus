
import Combine
import Foundation
import MnemonicSwift
import os
import React

var SynchronizerMap = [String: WalletSynchronizer]()

struct ConfirmedTx {
  var category: String?
  var status: String?
  var minedHeight: Int
  var toAddress: String?
  //var raw: String?
  var rawTransactionId: String
  var blockTimeInSeconds: Int
  var value: String
  var fee: String?
  var memos: [String]?
  var dictionary: [String: Any?] {
    return [
      "status": status,
      "category": category,
      "height": minedHeight,
      "address": toAddress,
      //"raw": raw,
      "txid": rawTransactionId,
      "time": blockTimeInSeconds,
      "amount": value,
      "fee": fee,
      "memos": memos ?? [],
    ]
  }
  var nsDictionary: NSDictionary {
    return dictionary as NSDictionary
  }
}

struct TotalBalances {
  var transparentAvailableZatoshi: Zatoshi
  var transparentTotalZatoshi: Zatoshi
  var saplingAvailableZatoshi: Zatoshi
  var saplingTotalZatoshi: Zatoshi
  var dictionary: [String: Any] {
    return [
      "transparentAvailableZatoshi": String(transparentAvailableZatoshi.amount),
      "transparentTotalZatoshi": String(transparentTotalZatoshi.amount),
      "saplingAvailableZatoshi": String(saplingAvailableZatoshi.amount),
      "saplingTotalZatoshi": String(saplingTotalZatoshi.amount),
    ]
  }
  var nsDictionary: NSDictionary {
    return dictionary as NSDictionary
  }
}

struct ProcessorState {
  var scanProgress: Int
  var networkBlockHeight: Int
  var lastScannedHeight: Int
  var dictionary: [String: Any] {
    return [
      "scanProgress": scanProgress,
      "networkBlockHeight": networkBlockHeight,
      "lastScannedHeight": lastScannedHeight
    ]
  }
  var nsDictionary: NSDictionary {
    return dictionary as NSDictionary
  }
}

// Used when calling reject where there isn't an error object
let genericError = NSError(domain: "", code: 0)

enum HexDataError: Error {
    case invalidLength
    case invalidByte(String)
}

@objc(VerusLightClient)
class VerusLightClient: RCTEventEmitter {

  override static func requiresMainQueueSetup() -> Bool {
    return true
  }
    

  private func getNetworkParams(_ network: String) -> ZcashNetwork {
    switch network {
    case "testnet":
      return ZcashNetworkBuilder.network(for: .testnet)
    default:
      return ZcashNetworkBuilder.network(for: .mainnet)
    }
  }

  // Synchronizer
  @objc func initialize(
    _ seed: String, _ wif: String, _ extsk: String, _ birthdayHeight: Int, _ alias: String, _ networkName: String,
    _ defaultHost: String, _ defaultPort: Int, _ newWallet: Bool,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      let network = getNetworkParams(networkName)
      let endpoint = LightWalletEndpoint(address: defaultHost, port: defaultPort, secure: true)
      let initializer = Initializer(
        cacheDbURL: try! cacheDbURLHelper(alias, network),
        fsBlockDbRoot: try! fsBlockDbRootURLHelper(alias, network),
        generalStorageURL: try! generalStorageURLHelper(alias, network),
        dataDbURL: try! dataDbURLHelper(alias, network),
        endpoint: endpoint,
        network: network,
        spendParamsURL: try! spendParamsURLHelper(alias),
        outputParamsURL: try! outputParamsURLHelper(alias),
        saplingParamsSourceURL: SaplingParamsSourceURL.default,
        alias: ZcashSynchronizerAlias.custom(alias)
      )
      if SynchronizerMap[alias] == nil {
        do {
          let wallet = try WalletSynchronizer(
            alias: alias, initializer: initializer, emitter: sendToJs)


          let extskBytes = try (extsk.isEmpty ? [] : bytes(from: extsk))
          let seedBytes = try (seed.isEmpty ? [] : Mnemonic.deterministicSeedBytes(from: seed))

          let initMode = newWallet ? WalletInitMode.newWallet : WalletInitMode.existingWallet

          clearLegacyDBs(networkName, alias)

          _ = try await wallet.synchronizer.prepare(
            //TODO extsk handling here, need to figure out "with/for" syntax
            transparent_key: [],
            extsk: extskBytes,
            seed: seedBytes,
            walletBirthday: birthdayHeight,
            for: initMode
          )

          try await wallet.synchronizer.start()
          wallet.subscribe()
          SynchronizerMap[alias] = wallet
          resolve(nil)
        } catch {
          reject("InitializeError", "Synchronizer failed to initialize", error)
        }
      } else {
        // Wallet already initialized
        resolve(nil)
      }
    }
  }

  @objc func start(
    _ alias: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          try await wallet.synchronizer.start()
          wallet.subscribe()
        } catch {
          reject("StartError", "Synchronizer failed to start", error)
        }
        resolve(nil)
      } else {
        reject("StartError", "Wallet does not exist", genericError)
      }
    }
  }

  @objc func stop(
    _ alias: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    let wallet = SynchronizerMap[alias]
      wallet?.synchronizer.stop()
      wallet?.cancellables.forEach { $0.cancel() }
      SynchronizerMap[alias] = nil
      resolve(nil)
  }

    @objc func stopAndDeleteWallet(
      _ alias: String,
      resolver resolve: @escaping RCTPromiseResolveBlock,
      rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
      guard let wallet = SynchronizerMap[alias] else {
        reject("DeleteWalletError", "Something went wrong trying to wipe DB", genericError)
        return
      }

      let _ = wallet.synchronizer.wipe()
      wallet.synchronizer.stop()

      let stoppedSub = wallet.synchronizer.stateStream
        .filter { state in state.syncStatus == .stopped }
        .prefix(1)
        .sink { _ in
            // only deallocate once we've stopped
            wallet.cancellables.forEach { $0.cancel() }
            SynchronizerMap[alias] = nil
        }

      wallet.cancellables.append(stoppedSub)

      resolve(true)
    }

  @objc func bech32Decode(
    _ bech32String: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    do {
      let (hrp, data) = try Bech32.decode(bech32String)
      let hex = data.map { String(format: "%02x", $0) }.joined()
      resolve(hex)
    } catch (let error) {
      reject("bech32DecodeError", "Decoding Bech32 string failed", error)
    }
  }

  @objc func getLatestNetworkHeight(
    _ alias: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          let height = try await wallet.synchronizer.latestHeight()
          resolve(height)
        } catch {
          reject("getLatestNetworkHeight", "Failed to query blockheight", error)
        }
      } else {
        reject("getLatestNetworkHeightError", "Wallet does not exist", genericError)
      }
    }
  }

  // A convenience method to get the block height when the synchronizer isn't running
  @objc func getBirthdayHeight(
    _ host: String, _ port: Int, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      do {
        let endpoint = LightWalletEndpoint(address: host, port: port, secure: true)
        let lightwalletd: LightWalletService = LightWalletGRPCService(endpoint: endpoint)
        let height = try await lightwalletd.latestBlockHeight()
        lightwalletd.closeConnection()
        resolve(height)
      } catch {
        reject("getLatestNetworkHeightGrpc", "Failed to query blockheight", error)
      }
    }
  }

  @objc func getInfo(
    _ alias: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          
          let networkHeight = try await wallet.synchronizer.latestHeight()
          let status = wallet.status
          
          let processorScannedHeight = wallet.processorState.lastScannedHeight
          var scanProgress = 0
          if status.description.lowercased() == "synced" {
            scanProgress = 100
          } else {
            scanProgress = Int(floor(try await wallet.synchronizer.linearScanProgressForNetworkHeight(networkHeight: networkHeight) * 1000) / 10)
          }
        
          let resultMap: [String: Any] = [
            "percent": scanProgress,
            "longestchain": Int(truncatingIfNeeded: networkHeight),
            "status": status.description.lowercased(),
            "blocks": Int(truncatingIfNeeded: processorScannedHeight)
          ]
    
          resolve(resultMap)
        } catch {
          reject("getInfoError", "Failed to getInfo", error)
        }
      } else {
        reject("getInfoError", "Wallet does not exist", genericError)
      }
    }
  }

  @objc func getPrivateBalance(
    _ alias: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          let saplingAvailable = wallet.balances.saplingAvailableZatoshi.amount
          let saplingTotal = wallet.balances.saplingTotalZatoshi.amount
          let saplingPending = saplingTotal - saplingAvailable

          let resultMap: [String: Any] = [
            "confirmed": String(saplingAvailable),
            "total": String(saplingTotal),
            "pending": String(saplingPending)
          ]

          resolve(resultMap)
        } catch {
          reject("getPrivateBalanceError", "Failed to getPrivateBalance", error)
        }
      } else {
        reject("getPrivateBalanceError", "Wallet does not exist", genericError)
      }
    }
  }

  @objc func getPrivateTransactions(
    _ alias: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          let txList = try await wallet.synchronizer.allTransactions()
          var out: [NSDictionary] = []
          let currentHeight = BlockHeight(wallet.processorState.networkBlockHeight)

          for tx in txList {
            if tx.isExpiredUmined ?? false { continue }

            do {
              var confTx = await wallet.parseTx(tx: tx)
              if tx.isPending(currentHeight: currentHeight) {
                confTx.status = "pending"
              } else {
                confTx.status = "confirmed"
              }
              out.append(confTx.nsDictionary)
            }
          }

          let resultMap: [String: Any] = [
            "transactions": NSArray(array: out)
          ]
          resolve(resultMap)
        } catch {
          reject("getPrivateTransactionsError", "Failed to collect transactions", error)
        }
      } else {
        reject("getPrivateTransactionsError", "Wallet does not exist", genericError)
      }
    }
  }

  @objc func sendToAddress(
    _ alias: String, _ zatoshi: String, _ toAddress: String, _ memo: String, _ extsk: String, _ mnemonicSeed: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        let amount = Int64(zatoshi)
        if amount == nil {
          reject("SpendToAddressError", "Amount is invalid", genericError)
          return
        }

        do {
          let spendingKey = try deriveUnifiedSpendingKey(extsk, mnemonicSeed, wallet.synchronizer.network)
          var sdkMemo: Memo? = nil
          if memo != "" {
            sdkMemo = try Memo(string: memo)
          }
          let broadcastTx = try await wallet.synchronizer.sendToAddress(
            spendingKey: spendingKey,
            zatoshi: Zatoshi(amount!),
            toAddress: Recipient(toAddress, network: wallet.synchronizer.network.networkType),
            memo: sdkMemo
          )

          let tx: NSMutableDictionary = ["txid": broadcastTx.rawID.toHexStringTxId()]
          if broadcastTx.raw != nil {
            tx["raw"] = broadcastTx.raw?.hexEncodedString()
          }
          resolve(tx)
        } catch {
          reject("SpendToAddressError", "Failed to spend", error)
        }
      } else {
        reject("SpendToAddressError", "Wallet does not exist", genericError)
      }
    }
  }

  /*@objc func shieldFunds(
    _ alias: String, _ seed: String, _ memo: String, _ threshold: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        if !wallet.fullySynced {
          reject("shieldFunds", "Wallet is not synced", genericError)
          return
        }

        do {
          let spendingKey = try deriveUnifiedSpendingKey(seed, wallet.synchronizer.network)
          let sdkMemo = try Memo(string: memo)
          let shieldingThreshold = Int64(threshold) ?? 10000

          let tx = try await wallet.synchronizer.shieldFunds(
            spendingKey: spendingKey,
            memo: sdkMemo,
            shieldingThreshold: Zatoshi(shieldingThreshold)
          )

          var confTx = await wallet.parseTx(tx: tx)

          // Hack: Memos aren't ready to be queried right after broadcast
          confTx.memos = [memo]
          resolve(confTx.nsDictionary)
        } catch {
          reject("shieldFunds", "Failed to shield funds", genericError)
        }
      } else {
        reject("shieldFunds", "Wallet does not exist", genericError)
      }
    }
  }*/

  @objc func rescan(
    _ alias: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        wallet.synchronizer.rewind(.birthday).sink(
          receiveCompletion: { completion in
            Task {
              switch completion {
              case .finished:
                wallet.status = "STOPPED"
                wallet.fullySynced = false
                wallet.restart = true
                wallet.initializeProcessorState()
                wallet.cancellables.forEach { $0.cancel() }
                try await wallet.synchronizer.start()
                wallet.subscribe()
                resolve(nil)
              case .failure:
                reject("RescanError", "Failed to rescan wallet", genericError)
              }
            }
          }, receiveValue: { _ in }
        ).store(in: &wallet.cancellables)
      } else {
        reject("RescanError", "Wallet does not exist", genericError)
      }
    }
  }

  
  private func bytes(from hex: String) throws -> [UInt8] {
      guard hex.count % 2 == 0 else { throw HexDataError.invalidLength }
      return try stride(from: 0, to: hex.count, by: 2).map { i in
          let start = hex.index(hex.startIndex, offsetBy: i)
          let end   = hex.index(start, offsetBy: 2)
          let byteStr = hex[start..<end]
          guard let b = UInt8(byteStr, radix: 16) else {
              throw HexDataError.invalidByte(String(byteStr))
          }
          return b
      }
  }
  
  // Derivation Tool
  private func getDerivationToolForNetwork(_ network: String) -> DerivationTool {
    switch network {
    case "testnet":
      return DerivationTool(networkType: ZcashNetworkBuilder.network(for: .testnet).networkType)
    default:
      return DerivationTool(networkType: ZcashNetworkBuilder.network(for: .mainnet).networkType)
    }
  }

  private func deriveUnifiedSpendingKey(_ extsk: String, _ seed: String, _ network: ZcashNetwork) throws
    -> UnifiedSpendingKey
  {
    //TODO: handle extsk bech32 decoding, and use Mnemonic.deterministicSeedBytes() to create byte array
    // then pass to deriveUnifiedSpendingKey

    let derivationTool = DerivationTool(networkType: network.networkType)
    let seedBytes = try (seed.isEmpty ? [] : Mnemonic.deterministicSeedBytes(from: seed))
    let extskBytes = try (extsk.isEmpty ? [] : bytes(from: extsk))
    
    //let extskBytes = try Mnemonic.deterministicSeedBytes(from: extsk)
      let spendingKey = try derivationTool.deriveUnifiedSpendingKey(transparent_key: [], extsk: extskBytes, seed: seedBytes, accountIndex: 0)
    return spendingKey
  }

  private func deriveSaplingSpendingKey(_ seed: String, _ network: ZcashNetwork) throws
    -> SaplingSpendingKey
  {
    //TODO: handle extsk bech32 decoding, and use Mnemonic.deterministicSeedBytes() to create byte array
    // then pass to deriveUnifiedSpendingKey
    let derivationTool = DerivationTool(networkType: network.networkType)
    let seedBytes = try Mnemonic.deterministicSeedBytes(from: seed)
    let spendingKey = try derivationTool.deriveSaplingSpendingKey(seed: seedBytes, accountIndex: 0)
    return spendingKey
  }

  private func deriveUnifiedViewingKey(_ extsk: String, _ seed: String, _ network: ZcashNetwork) throws
    -> UnifiedFullViewingKey
  {
    let spendingKey = try deriveUnifiedSpendingKey(extsk, seed, network)
    let derivationTool = DerivationTool(networkType: network.networkType)
    let viewingKey = try derivationTool.deriveUnifiedFullViewingKey(from: spendingKey)
    return viewingKey
  }

  @objc func deriveViewingKey(
    _ extsk: String, _ seed: String, _ network: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    do {
      let zcashNetwork = getNetworkParams(network)
      let viewingKey = try deriveUnifiedViewingKey(extsk, seed, zcashNetwork)
      resolve(viewingKey.stringEncoded)
    } catch {
      reject("DeriveViewingKeyError", "Failed to derive viewing key", error)
    }
  }

  @objc func deriveUnifiedSpendingKey(
    _ extsk: String, _ seed: String, _ network: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    do {
      let zcashNetwork = getNetworkParams(network)
      let spendingKey = try deriveUnifiedSpendingKey(extsk, seed, zcashNetwork)
        let hex = spendingKey.bytes.map { String(format: "%02x", $0) }.joined()
      resolve(spendingKey)
    } catch {
      reject("DeriveSpendingKeyError", "Failed to derive spending key", error)
    }
  }

  @objc func deriveSaplingSpendingKey(
    _ seed: String, _ network: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    do {
      let zcashNetwork = getNetworkParams(network)
      let spendingKey = try deriveSaplingSpendingKey(seed, zcashNetwork)
      let hex = spendingKey.bytes.map { String(format: "%02x", $0) }.joined()
      resolve(hex)
    } catch {
      reject("DeriveSpendingKeyError", "Failed to derive spending key", error)
    }
  }

  @objc func deriveUnifiedAddress(
    _ alias: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          let unifiedAddress = try await wallet.synchronizer.getUnifiedAddress(accountIndex: 0)
          let saplingAddress = try await wallet.synchronizer.getSaplingAddress(accountIndex: 0)
          let transparentAddress = try await wallet.synchronizer.getTransparentAddress(
            accountIndex: 0)
          let addresses: NSDictionary = [
            "unifiedAddress": unifiedAddress.stringEncoded,
            "saplingAddress": saplingAddress.stringEncoded,
            "transparentAddress": transparentAddress.stringEncoded,
          ]
          resolve(addresses)
          return
        } catch {
          reject("deriveUnifiedAddress", "Failed to derive unified address", error)
        }
      } else {
        reject("deriveUnifiedAddress", "Wallet does not exist", genericError)
      }
    }
  }

  @objc func deriveSaplingAddress(
    _ alias: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    Task {
      if let wallet = SynchronizerMap[alias] {
        do {
          let saplingAddress = try await wallet.synchronizer.getSaplingAddress(accountIndex: 0)
          resolve(saplingAddress.stringEncoded)
          return
        } catch {
          reject("deriveSaplingAddress", "Failed to derive sapling address", error)
        }
      } else {
        reject("deriveSaplingAddress", "Wallet does not exist", genericError)
      }
    }
  }

  @objc func deriveShieldedAddress(
    _ extsk: String, _ seed: String, _ network: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    do {
      let zcashNetwork = getNetworkParams(network)
      let viewingKey = try deriveUnifiedViewingKey(extsk, seed, zcashNetwork)
      let ufvk = viewingKey.stringEncoded
      let derivationTool = DerivationTool(networkType: zcashNetwork.networkType)
      let saplingAddress = try derivationTool.deriveShieldedAddress(from: ufvk)
      resolve(saplingAddress)
    } catch {
      reject("DeriveShieldedAddressError", "Failed to derive shieldedAddress", error)
    }
  }

  @objc func isValidAddress(
    _ address: String, _ network: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    //TODO: this needs modified probably, needed a change in Android SDK
    let derivationTool = getDerivationToolForNetwork(network)
    if derivationTool.isValidUnifiedAddress(address)
      || derivationTool.isValidSaplingAddress(address)
      || derivationTool.isValidTransparentAddress(address)
    {
      resolve(true)
    } else {
      resolve(false)
    }
  }

  @objc func deterministicSeedBytes(
    _ seed: String, resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    do {
      let seedBytes = try Mnemonic.deterministicSeedBytes(from: seed)
      let hexString = seedBytes.map { String(format: "%02x", $0) }.joined()
      resolve(hexString)
    } catch {
      reject("DeterministicSeedBytesError", "Failed to calculate deterministicSeedBytes from mnemonic", error)
    }
  }

  // Events
  public func sendToJs(name: String, data: Any) {
    self.sendEvent(withName: name, body: data)
  }

  override func supportedEvents() -> [String] {
    return ["BalanceEvent", "StatusEvent", "TransactionEvent", "UpdateEvent"]
  }
}

class WalletSynchronizer: NSObject {
  public var alias: String
  public var synchronizer: SDKSynchronizer
  var status: String
  var emit: (String, Any) -> Void
  var fullySynced: Bool
  var restart: Bool
  var processorState: ProcessorState
  var cancellables: [AnyCancellable] = []
  var balances: TotalBalances

  init(alias: String, initializer: Initializer, emitter: @escaping (String, Any) -> Void) throws {
    self.alias = alias
    self.synchronizer = SDKSynchronizer(initializer: initializer)
    self.status = "STOPPED"
    self.emit = emitter
    self.fullySynced = false
    self.restart = false
    self.processorState = ProcessorState(
      scanProgress: 0,
      networkBlockHeight: 0,
      lastScannedHeight: 0
    )
    self.balances = TotalBalances(
      transparentAvailableZatoshi: Zatoshi(0),
      transparentTotalZatoshi: Zatoshi(0),
      saplingAvailableZatoshi: Zatoshi(0),
      saplingTotalZatoshi: Zatoshi(0))
  }

  public func subscribe() {
    self.synchronizer.stateStream
      .sink(receiveValue: { [weak self] state in self?.updateSyncStatus(event: state) })
      .store(in: &cancellables)
    self.synchronizer.stateStream
      .sink(receiveValue: { [weak self] state in self?.updateProcessorState(event: state) })
      .store(in: &cancellables)
    self.synchronizer.eventStream
      .sink { SynchronizerEvent in
        switch SynchronizerEvent {
        case .minedTransaction(let transaction):
          self.emitTxs(transactions: [transaction])
        case .foundTransactions(let transactions, _):
          self.emitTxs(transactions: transactions)
        default:
          return
        }
      }
      .store(in: &cancellables)
  }

  func updateSyncStatus(event: SynchronizerState) {
    var status = self.status

    if !self.fullySynced {
      switch event.internalSyncStatus {
      case .syncing:
        status = "SYNCING"
        self.restart = false
      case .synced:
        if self.restart {
          // The synchronizer emits "synced" status after starting a rescan. We need to ignore these.
          return
        }
        status = "SYNCED"

        self.fullySynced = true
      default:
        break
      }

      if status == self.status { return }
      self.status = status
      let data: NSDictionary = ["alias": self.alias, "name": self.status]
      emit("StatusEvent", data)
    }
  }

  func updateProcessorState(event: SynchronizerState) {
    var scanProgress = 0

    switch event.internalSyncStatus {
    case .syncing(let progress):
      scanProgress = Int(floor(progress * 100))
    case .synced:
      scanProgress = 100
    case .unprepared, .disconnected, .stopped:
      scanProgress = 0
    default:
      return
    }

    if scanProgress == self.processorState.scanProgress
      && event.latestBlockHeight == self.processorState.networkBlockHeight
    {
      return
    }

    self.processorState = ProcessorState(
      scanProgress: scanProgress, networkBlockHeight: event.latestBlockHeight, lastScannedHeight: event.lastScannedHeight)
    let data: NSDictionary = [
      "alias": self.alias, "scanProgress": self.processorState.scanProgress,
      "networkBlockHeight": self.processorState.networkBlockHeight,
      "lastScannedHeight": self.processorState.lastScannedHeight
    ]
    emit("UpdateEvent", data)
    updateBalanceState(event: event)
  }

  func initializeProcessorState() {
    self.processorState = ProcessorState(
      scanProgress: 0,
      networkBlockHeight: 0,
      lastScannedHeight: 0
    )
    self.balances = TotalBalances(
      transparentAvailableZatoshi: Zatoshi(0),
      transparentTotalZatoshi: Zatoshi(0),
      saplingAvailableZatoshi: Zatoshi(0),
      saplingTotalZatoshi: Zatoshi(0))
  }

  func updateBalanceState(event: SynchronizerState) {
    //let transparentBalance = event.transparentBalance
    //let shieldedBalance = event.shieldedBalance
      let transparentBalance = event.accountBalance?.unshielded ?? Zatoshi(0)
      let shieldedBalance = event.accountBalance?.saplingBalance ?? PoolBalance.zero
      let orchardBalance = event.accountBalance?.orchardBalance ?? PoolBalance.zero
      let transparentAvailableZatoshi = transparentBalance
      let transparentTotalZatoshi = transparentBalance

      let saplingAvailableZatoshi = shieldedBalance.spendableValue
      let saplingTotalZatoshi = shieldedBalance.total()

      //let orchardAvailableZatoshi = orchardBalance.spendableValue
      //let orchardTotalZatoshi = orchardBalance.total()

      self.balances = TotalBalances(
        transparentAvailableZatoshi: transparentAvailableZatoshi,
        transparentTotalZatoshi: transparentTotalZatoshi,
        saplingAvailableZatoshi: saplingAvailableZatoshi,
        saplingTotalZatoshi: saplingTotalZatoshi
      )
      let data = NSMutableDictionary(dictionary: self.balances.nsDictionary)
      data["alias"] = self.alias
      emit("BalanceEvent", data)
      //let transparentAvailableZatoshi = transparentBalance.verified
    //let transparentTotalZatoshi = transparentBalance.total

    //let saplingAvailableZatoshi = shieldedBalance.verified
    //let saplingTotalZatoshi = shieldedBalance.total

    /*if transparentAvailableZatoshi == self.balances.transparentAvailableZatoshi
      && transparentTotalZatoshi == self.balances.transparentTotalZatoshi
      && saplingAvailableZatoshi == self.balances.saplingAvailableZatoshi
      && saplingTotalZatoshi == self.balances.saplingTotalZatoshi
    {
      return
    }

    self.balances = TotalBalances(
      transparentAvailableZatoshi: transparentAvailableZatoshi,
      transparentTotalZatoshi: transparentTotalZatoshi,
      saplingAvailableZatoshi: saplingAvailableZatoshi,
      saplingTotalZatoshi: saplingTotalZatoshi
    )
    let data = NSMutableDictionary(dictionary: self.balances.nsDictionary)
    data["alias"] = self.alias
    emit("BalanceEvent", data)
     */
  }

  func parseTx(tx: ZcashTransaction.Overview) async -> ConfirmedTx {
    var confTx = ConfirmedTx(
      minedHeight: tx.minedHeight ?? 0,
      rawTransactionId: (tx.rawID.toHexStringTxId()),
      blockTimeInSeconds: Int(tx.blockTime ?? 0),
      value: String(describing: abs(tx.value.amount))
    )
    /*if tx.raw != nil {
      confTx.raw = tx.raw!.hexEncodedString()
    }*/
    if tx.fee != nil {
      confTx.fee = String(describing: abs(tx.fee!.amount))
    }
    if tx.isSentTransaction {
      confTx.category = "sent"
      let recipients = await self.synchronizer.getRecipients(for: tx)
      if recipients.count > 0 {
        let addresses = recipients.compactMap {
          if case let .address(address) = $0 {
            return address
          } else {
            return nil
          }
        }
        if addresses.count > 0 {
          confTx.toAddress = addresses.first!.stringEncoded
        }
      }
    } else {
        confTx.toAddress = try? await self.synchronizer.getSaplingAddress(accountIndex: Int(0)).stringEncoded
        confTx.category = "received"
    }


    if tx.memoCount > 0 {
      let memos = (try? await self.synchronizer.getMemos(for: tx)) ?? []
      let textMemos = memos.compactMap {
        return $0.toString()
      }
       /* var seen = Set<String>()
        let unique = textMemos.compactMap { memo -> String? in
          let trimmed = memo.trimmingCharacters(in: .whitespacesAndNewlines)
          guard !trimmed.isEmpty, !seen.contains(trimmed) else { return nil }
          seen.insert(trimmed)
          return trimmed
        }
        confTx.memos = unique*/
      confTx.memos = textMemos
    }
    return confTx
  }

  func emitTxs(transactions: [ZcashTransaction.Overview]) {
    Task {
      var out: [NSDictionary] = []
      for tx in transactions {
        if tx.isExpiredUmined ?? false { continue }
        let confTx = await parseTx(tx: tx)
        out.append(confTx.nsDictionary)
      }

      let data: NSDictionary = ["alias": self.alias, "transactions": NSArray(array: out)]
      emit("TransactionEvent", data)
    }
  }
}

// Local file helper funcs
func documentsDirectoryHelper() throws -> URL {
  try FileManager.default.url(
    for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
}

func cacheDbURLHelper(_ alias: String, _ network: ZcashNetwork) throws -> URL {
  try documentsDirectoryHelper()
    .appendingPathComponent(
        network.constants.defaultDbNamePrefix + alias + ZcashSDK.defaultCacheDbName,
      isDirectory: false
    )
}

func dataDbURLHelper(_ alias: String, _ network: ZcashNetwork) throws -> URL {
  try documentsDirectoryHelper()
    .appendingPathComponent(
      network.constants.defaultDbNamePrefix + alias + ZcashSDK.defaultDataDbName,
      isDirectory: false
    )
}

func spendParamsURLHelper(_ alias: String) throws -> URL {
  try documentsDirectoryHelper().appendingPathComponent(
    alias + "sapling-spend.params"
  )
}

func outputParamsURLHelper(_ alias: String) throws -> URL {
  try documentsDirectoryHelper().appendingPathComponent(
    alias + "sapling-output.params"
  )
}

func fsBlockDbRootURLHelper(_ alias: String, _ network: ZcashNetwork) throws -> URL {
  try documentsDirectoryHelper()
    .appendingPathComponent(
      network.constants.defaultDbNamePrefix + alias + ZcashSDK.defaultFsCacheName,
      isDirectory: true
    )
}

func generalStorageURLHelper(_ alias: String, _ network: ZcashNetwork) throws -> URL {
  try documentsDirectoryHelper()
    .appendingPathComponent(
      network.constants.defaultDbNamePrefix + alias + "general_storage",
      isDirectory: true
    )
}

func clearLegacyDBs(_ networkName: String, _ alias: String) {
  let fm = FileManager.default
  do {
    let docs = try documentsDirectoryHelper()
    let contents = (try? fm.contentsOfDirectory(at: docs, includingPropertiesForKeys: nil)) ?? []

    // Legacy pattern: <NETWORK>_<network>_<alias>_<suffix>
    let networkPrefix = networkName.uppercased()
    let networkLower = networkName.lowercased()

    let legacyTargets = [
      "\(networkPrefix)_\(networkLower)_\(alias)_pending.db",
      "\(networkPrefix)_\(networkLower)_\(alias)_fs_cache",
      "\(networkPrefix)_\(networkLower)_\(alias)_caches.db",
      "\(networkPrefix)_\(networkLower)_\(alias)_data.db"
    ]

    for url in contents {
      guard legacyTargets.contains(url.lastPathComponent) else { continue }

      if fm.fileExists(atPath: url.path) {
        do {
          try fm.removeItem(at: url)
          NSLog("clearLegacyDBs: Removed legacy file \(url.lastPathComponent)")
        } catch {
          NSLog("clearLegacyDBs: Could not remove \(url.lastPathComponent): \(error.localizedDescription)")
        }
      }
    }
  } catch {
    NSLog("clearLegacyDBs: Unable to enumerate Documents directory: \(error.localizedDescription)")
  }
}
