package com.verusmobile.veruslightclient

import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.exception.LightWalletException
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.ext.*
import cash.z.ecc.android.sdk.internal.*
import cash.z.ecc.android.sdk.model.*
import cash.z.ecc.android.sdk.tool.DerivationTool
import cash.z.ecc.android.sdk.type.*
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.new
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.math.roundToInt
import kotlin.math.min

import android.util.Log
import java.lang.Error


private val initializationJobs = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

private class WalletClosedException(val alias: String) : IllegalStateException("Wallet closed or never initialized for alias: $alias")

private suspend fun awaitWalletReady(alias: String) {
    val job = initializationJobs[alias] ?: throw WalletClosedException(alias)
    job.await()
}

private val collectorScopes = mutableMapOf<String, CoroutineScope>()

// Uncomment the OptIn below if you want to use 'byteArray.toHexString()' outside of deterministicSeedBytes function
// i.e. in logging. This function was not promoted to stable in Kotlin Standard until Kotlin 2.2. Alternatively, you can
// OptIn on a per-function basis

//@OptIn(kotlin.ExperimentalStdlibApi::class)
class VerusLightClient(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {
    /**
     * Scope for anything that out-lives the synchronizer, meaning anything that can be used before
     * the synchronizer starts or after it stops. Everything else falls within the scope of the
     * synchronizer and should use `synchronizer.coroutineScope` whenever a scope is needed.
     */
    private var moduleScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private var synchronizerMap = mutableMapOf<String, SdkSynchronizer>()

    private val networks = mapOf("mainnet" to ZcashNetwork.Mainnet, "testnet" to ZcashNetwork.Testnet)

    override fun getName() = "VerusLightClient"

    @ReactMethod
    fun initialize(
        seed: String,
        wif: String,
        extsk: String,
        birthdayHeight: Int,
        alias: String,
        networkName: String = "VRSC",
        defaultHost: String = "lwdlegacy.blur.cash",
        defaultPort: Int = 443,
        newWallet: Boolean,
        promise: Promise,
    ) = moduleScope.launch {
        try {
            val ready = CompletableDeferred<Unit>()
            initializationJobs[alias]?.cancel() // cancel stale latch if any
            initializationJobs[alias] = ready

            val network = networks.getOrDefault(networkName, ZcashNetwork.Mainnet)
            val endpoint = LightWalletEndpoint(defaultHost, defaultPort, true)
            var seedPhrase = byteArrayOf()
            var extendedSecretKey = byteArrayOf()
            var transparentKey = byteArrayOf()

            // check presence of data, for each key import method/type
            // pass through empty array if data is not present

            if (!seed.isNullOrEmpty()) {
                seedPhrase = SeedPhrase.new(seed).toByteArray()
            }
            if(!extsk.isNullOrEmpty()) {
                extendedSecretKey = SeedPhrase.new(extsk).toByteArray()
            }
            if (!wif.isNullOrEmpty()) {
                val decodedWif = wif.decodeBase58WithChecksum()
                transparentKey = decodedWif.copyOfRange(1, decodedWif.size)
            }

            val initMode = if (newWallet) WalletInitMode.NewWallet else WalletInitMode.ExistingWallet

            val sync = Synchronizer.new(
                reactApplicationContext,
                network,
                alias,
                endpoint,
                seedPhrase,
                BlockHeight.new(network, birthdayHeight.toLong()),
                initMode,
                transparentKey,
                extendedSecretKey
            ) as SdkSynchronizer

            // zero our intermediate variables
            seedPhrase.fill(0)
            extendedSecretKey.fill(0)
            transparentKey.fill(0)

            synchronizerMap[alias] = sync

            collectorScopes.remove(alias)?.let { scope ->
                (scope.coroutineContext[Job])?.cancelChildren()
            }

            val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            collectorScopes[alias] = collectorScope

            val syncScope = sync.coroutineScope

            syncScope.launch {
                sync.status
                    .filter {
                        it == Synchronizer.Status.DISCONNECTED ||
                        it == Synchronizer.Status.INITIALIZING ||
                        it == Synchronizer.Status.SYNCING ||
                        it == Synchronizer.Status.SYNCED
                    }
                    .first()
                if (!ready.isCompleted) ready.complete(Unit)
                Log.i("ReactNative", "Synchronizer $alias initialized and ready.")
            }

            ready.await()
            promise.resolve(true)

            val wallet = getWallet(alias)

            collectorScope.launch {
                combine(wallet.progress, wallet.networkHeight) { progress, networkHeight ->
                    mapOf("progress" to progress, "networkHeight" to networkHeight)
                }.collect { map ->
                    val progress = map["progress"] as PercentDecimal
                    val networkBlockHeight = (map["networkHeight"] as? BlockHeight)
                        ?: BlockHeight.new(wallet.network, birthdayHeight.toLong())

                    sendEvent("UpdateEvent") { args ->
                        args.putString("alias", alias)
                        args.putInt("scanProgress", progress.toPercentage())
                        args.putInt("networkBlockHeight", networkBlockHeight.value.toInt())
                    }
                }
            }

            collectorScope.launch {
                wallet.status.collect { status ->
                    sendEvent("StatusEvent") { args ->
                        args.putString("alias", alias)
                        args.putString("name", status.toString())
                    }
                }
            }

            collectorScope.launch {
                wallet.transactions.collect { txList ->
                    val parsedMaps = txList
                        .filter { tx -> tx.transactionState != TransactionState.Expired }
                        .map { tx ->
                            async {
                                parseTx(wallet, tx)
                            }
                        }
                        .awaitAll()
                    val nativeArray = Arguments.createArray()
                    parsedMaps.forEach { map ->
                        nativeArray.pushMap(map)
                    }

                    sendEvent("TransactionEvent") { args ->
                        args.putString("alias", alias)
                        args.putArray("transactions", nativeArray)
                    }
                }
            }

            collectorScope.launch {
                combine(wallet.transparentBalance, wallet.saplingBalances) { transparent, sapling ->
                    Balances(transparentBalance = transparent, saplingBalances = sapling)
                }.collect { balances ->
                    val tAvail = balances.transparentBalance ?: Zatoshi(0L)
                    val sAvail = balances.saplingBalances?.available ?: Zatoshi(0L)
                    val sTotal = balances.saplingBalances?.total ?: Zatoshi(0L)

                    sendEvent("BalanceEvent") { args ->
                        args.putString("alias", alias)
                        args.putString("transparentAvailableZatoshi", tAvail.value.toString())
                        args.putString("saplingAvailableZatoshi", sAvail.value.toString())
                        args.putString("saplingTotalZatoshi", sTotal.value.toString())
                    }
                }
            }

        } catch (t: Throwable) {
            Log.e("ReactNative", "Error initializing wallet $alias", t)
            promise.reject("INIT_ERROR", t.localizedMessage, t)
        }
    }

    @ReactMethod
    fun getInfo(alias: String, promise: Promise) {
        moduleScope.launch {
            try {
                if (!synchronizerMap.containsKey(alias)) {
                    promise.reject("WALLET_CLOSED", "Wallet not initialized for alias: $alias")
                    return@launch
                }
                awaitWalletReady(alias) // throws WalletClosedException if closed

                val wallet = getWallet(alias)
                val birthdayHeight = wallet.latestBirthdayHeight;
                val latestHeight: BlockHeight = wallet.latestHeight ?: BlockHeight.new(wallet.network, birthdayHeight.value)

                val map = combine(
                    wallet.processorInfo,
                    //wallet.progress,
                    //wallet.networkHeight,
                    wallet.status
                ) { processorInfo, /*progress, networkHeight,*/ status ->
                    mapOf(
                        "processorInfo" to processorInfo,
                        //"progress" to progress,
                        //"networkHeight" to (networkHeight ?: BlockHeight.new(wallet.network, birthdayHeight.value)),
                        "status" to status
                    )
                }.first()

                val processorInfo = map["processorInfo"] as CompactBlockProcessor.ProcessorInfo
                val processorNetworkHeight = processorInfo.networkBlockHeight?: BlockHeight.new(wallet.network, birthdayHeight.value)
                val processorScannedHeight = processorInfo.lastScannedHeight?: BlockHeight.new(wallet.network, birthdayHeight.value)
                //val progress = map["progress"] as PercentDecimal
                //val networkBlockHeight = map["networkHeight"] as BlockHeight
                val status = map["status"]

                //Log.d("ReactNative", "processorInfo: networkHeight(${processorNetworkHeight.value})")
                //Log.d("ReactNative", "processorInfo: overallSyncRange(${processorInfo.overallSyncRange})")
                //Log.d("ReactNative", "processorInfo: lastScannedHeight(${processorScannedHeight.value})")
                //Log.d("ReactNative", "processorInfo: firstUnenhancedHeight(${firstUnenhancedHeight.value})")
                //Log.d("ReactNative", "progress.toPercentage(): ${progress.toPercentage()}")
                //Log.d("ReactNative", "networkBlockHeight: ${networkBlockHeight.value.toInt()}")
                //Log.d("ReactNative", "latestBlockHeight: ${latestHeight.value.toInt()}")
                //Log.d("ReactNative", "wallet status: ${status.toString().lowercase()}")

                val walletBirthday = birthdayHeight.value.toLong()
                val scannedHeight = processorScannedHeight.value.toLong()
                val networkHeight = processorNetworkHeight.value.toLong()

                val progressDouble: Double =
                    if (scannedHeight <= walletBirthday || networkHeight <= walletBirthday) {
                        0.0
                    } else {
                        val ratio =
                            (scannedHeight).toDouble() /
                                    (networkHeight).toDouble()
                        val pct = (ratio * 10000.0).roundToInt() / 100.0
                        // clamp to 100.00
                        min(pct, 100.00)
                    }

                //Log.d("ReactNative", "progressDouble: ${progressDouble}")

                val resultMap = Arguments.createMap().apply {
                    putDouble("percent", progressDouble)
                    putString("longestchain", networkHeight.toString())
                    putString("status", status.toString().lowercase())
                    putString("blocks", scannedHeight.toString())
                }

                promise.resolve(resultMap)
            } catch (e: WalletClosedException) {
                 promise.reject("WALLET_CLOSED", e.message, e)
            } catch (e: Exception) {
                Log.e("ReactNative", "getInfo failed", e)
                promise.reject("GET_INFO_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getPrivateBalance(alias: String, promise: Promise) {
        moduleScope.launch {
            try {
                if (!synchronizerMap.containsKey(alias)) {
                    promise.reject("WALLET_CLOSED", "Wallet not initialized for alias: $alias")
                    return@launch
                }

                awaitWalletReady(alias)

                val wallet = getWallet(alias)

                val saplingBalances = wallet.saplingBalances.firstOrNull()
                val saplingAvailableZatoshi = saplingBalances?.available ?: Zatoshi(0L)
                val saplingTotalZatoshi = saplingBalances?.total ?: Zatoshi(0L)

                //Log.w("ReactNative", "saplingBalanceAvailable: ${saplingBalances!!.available.value}")
                //Log.w("ReactNative", "saplingBalanceAvailable(Zatoshi): ${saplingAvailableZatoshi}")
                //Log.w("ReactNative", "saplingBalanceTotal: ${saplingBalances!!.total.value}")
                //Log.w("ReactNative", "saplingBalanceTotal(Zatoshi): ${saplingTotalZatoshi}")
                //Log.w("ReactNative", "saplingBalanceChangePending: ${saplingBalances!!.changePending}")
                //Log.w("ReactNative", "saplingBalanceValuePending: ${saplingBalances!!.valuePending}")

                val map = Arguments.createMap().apply {
                    putString("confirmed", saplingAvailableZatoshi.value.toString())
                    putString("total", saplingTotalZatoshi.value.toString())
                    putString("pending", saplingTotalZatoshi.minus(saplingAvailableZatoshi).value.toString())
                }

                promise.resolve(map)

            } catch (e: WalletClosedException) {
                 promise.reject("WALLET_CLOSED", e.message, e)
            } catch (e: Exception) {
                Log.e("ReactNative", "getPrivateBalance failed", e)
                promise.reject("GET_PRIVATE_BALANCE_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getPrivateTransactions(alias: String, promise: Promise) {
        moduleScope.launch {
            try {
                if (!synchronizerMap.containsKey(alias)) {
                    promise.reject("WALLET_CLOSED", "Wallet not initialized for alias: $alias")
                    return@launch
                }

                awaitWalletReady(alias)

                val wallet = getWallet(alias)

                val txList = wallet.transactions.first()
                val nativeArray = Arguments.createArray()

                for (tx in txList) {
                    if (tx.transactionState != TransactionState.Expired) {
                        try {
                            val parsedTx = parseTx(wallet, tx)
                            nativeArray.pushMap(parsedTx)
                        } catch (t: Throwable) {
                            Log.w("ReactNative", "Could not parse TX: ${t.localizedMessage}")
                        }
                    }
                }
                val result = Arguments.createMap().apply {
                    putArray("transactions", nativeArray)
                }
                promise.resolve(result)
            } catch (e: WalletClosedException) {
                 promise.reject("WALLET_CLOSED", e.message, e)
            } catch (e: Exception) {
                Log.e("ReactNative", "getPrivateTransactions failed", e)
                promise.reject("GET_PRIVATE_TRANSACTIONS_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun stop(
        alias: String,
        promise: Promise,
    ) {
        moduleScope.launch(Dispatchers.IO) {
            try {
                val wallet = getWallet(alias)

                collectorScopes.remove(alias)?.let { scope ->
                    (scope.coroutineContext[Job])?.cancelChildren()
                }

                wallet.closeFlow().firstOrNull()
                wallet.status.filter { it == Synchronizer.Status.STOPPED }.first()

                synchronizerMap.remove(alias)
                initializationJobs[alias]?.completeExceptionally(Exception("Wallet closed"))
                initializationJobs.remove(alias)

                Log.i("ReactNative", "Synchronizer $alias stopped and cleaned up.")
                promise.resolve("STOPPED")
            } catch (t: Throwable) {
                Log.e("ReactNative", "Error stopping synchronizer $alias", t)
                promise.reject("STOP_ERROR", t.localizedMessage, t)
            }
        }
    }
    
    //TODO: consider adding boolean argument to conditionally include rawTxData
    private suspend fun parseTx(
        wallet: SdkSynchronizer,
        tx: TransactionOverview,
    ): WritableMap {
        val map = Arguments.createMap()
        try {
            map.putString("amount", tx.netValue.value.toString())

            tx.feePaid?.let {
                map.putString("fee", it.value.toString())
            }

            map.putInt("height", tx.minedHeight?.value?.toInt() ?: 0)

            map.putString(
                "status",
                if (tx.transactionState == TransactionState.Confirmed) "confirmed" else "pending"
            )

            map.putInt("time", tx.blockTimeEpochSeconds?.toInt() ?: 0)
            map.putString("txid", tx.rawId.byteArray.toHexReversed())

            /*tx.raw?.let {
                map.putString("raw", it.byteArray.toHex())
            }*/

            //TODO: investigate why the above was causing partial data return...
            // commenting it out solved the issue where (amount, txid, statu, time, height) was being returned for only one transfer
            // latter tx only had category, empty memos, and rawTxData

            if (tx.isSentTransaction) {
                map.putString("category", "sent")
                try {
                    val recipient = wallet.getRecipients(tx).firstOrNull()
                    if (recipient is TransactionRecipient.Address) {
                        map.putString("address", recipient.addressValue)
                    }
                } catch (t: Throwable) {
                    Log.w("ReactNative", "Could not get recipient: ${t.localizedMessage}")
                }
            } else {
                map.putString("category", "received")
                map.putString("address", wallet.getSaplingAddress(Account(0)))
                //TODO: this needs changed if we have additional account indices at some point
            }

            if (tx.memoCount > 0) {
                try {
                    val memos = wallet.getMemos(tx).take(tx.memoCount).toList()
                    map.putArray("memos", Arguments.fromList(memos))
                } catch (t: Throwable) {
                    Log.w("ReactNative", "Could not get memos: ${t.localizedMessage}")
                    map.putArray("memos", Arguments.createArray())
                }
            } else {
                map.putArray("memos", Arguments.createArray())
            }

        } catch (e: Exception) {
            Log.w("ReactNative", "Exception while parsing tx: ${e.localizedMessage}")
        }

        return map
    }

    @ReactMethod
    fun rescan(
        alias: String,
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            promise.wrap {
                wallet.rewindToNearestHeight(wallet.latestBirthdayHeight)
                return@wrap null
            }
        }
    }

    @ReactMethod
    fun deriveViewingKey(
        extsk: String,
        seed: String,
        network: String = "VRSC",
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                var seedPhrase = byteArrayOf()
                var extendedSecretKey = byteArrayOf()
                if (!seed.isNullOrEmpty()) {
                    seedPhrase = SeedPhrase.new(seed).toByteArray()
                }
                if (!extsk.isNullOrEmpty()) {
                    extendedSecretKey = SeedPhrase.new(extsk).toByteArray()
                }
                val spendingKey =
                    DerivationTool.getInstance().deriveUnifiedSpendingKey(
                        byteArrayOf(),
                        extendedSecretKey,
                        seedPhrase,
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account.DEFAULT,
                    )

                // zero our intermediate variables
                seedPhrase.fill(0)
                extendedSecretKey.fill(0)

                val keys =
                    DerivationTool.getInstance().deriveUnifiedFullViewingKey(
                        spendingKey,
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                    )
                return@wrap keys.encoding
            }
        }
    }


    @OptIn(kotlin.ExperimentalStdlibApi::class)
    @ReactMethod
    fun deriveSaplingSpendingKey(
        seed: String,
        network: String,
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                Log.w("ReactNative", "deriveSaplingSpendingKey called!")
                val key =
                    DerivationTool.getInstance().deriveSaplingSpendingKey(
                        SeedPhrase.new(seed).toByteArray(),
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account.DEFAULT,
                    )

                val result = Arguments.createMap().apply {
                    putInt("account", key.account.value)
                    putString("extsk", key.copyBytes().toHexString())
                }
                return@wrap result
            }
        }
    }

    //
    // Properties
    //

    @ReactMethod
    fun getLatestNetworkHeight(
        alias: String,
        promise: Promise,
    ) = promise.wrap {
        val wallet = getWallet(alias)
        return@wrap wallet.latestHeight
    }

    @ReactMethod
    fun getBirthdayHeight(
        host: String,
        port: Int,
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                val endpoint = LightWalletEndpoint(host, port, true)
                val lightwalletService = LightWalletClient.new(reactApplicationContext, endpoint)
                return@wrap when (val response = lightwalletService.getLatestBlockHeight()) {
                    is Response.Success -> {
                        response.result.value.toInt()
                    }

                    is Response.Failure -> {
                        throw LightWalletException.DownloadBlockException(
                            response.code,
                            response.description,
                            response.toThrowable(),
                        )
                    }
                }
            }
        }
    }

    @ReactMethod
    fun proposeTransfer(
        alias: String,
        zatoshi: String,
        toAddress: String,
        memo: String = "",
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            try {
                val proposal =
                    wallet.proposeTransfer(
                        Account.DEFAULT,
                        toAddress,
                        Zatoshi(zatoshi.toLong()),
                        memo,
                    )
                val map = Arguments.createMap()
                map.putInt("transactionCount", proposal.transactionCount())
                map.putString("totalFee", proposal.totalFeeRequired().value.toString())
                promise.resolve(map)
            } catch (t: Throwable) {
                promise.reject("Err", t)
            }
        }
    }

    @ReactMethod
    fun sendToAddress(
        alias: String,
        zatoshi: String,
        toAddress: String,
        memo: String = "",
        extsk: String,
        seed: String,
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            try {
                var extendedSecretKey = byteArrayOf()
                var seedPhrase = byteArrayOf()
                var transparentKey = byteArrayOf()

                if (!seed.isNullOrEmpty()) {
                    seedPhrase = SeedPhrase.new(seed).toByteArray()
                }
                if (!extsk.isNullOrEmpty()) {
                    extendedSecretKey = SeedPhrase.new(extsk).toByteArray()
                }
                /*if (!wif.isNullOrEmpty()) {
                    val decodedWif = wif.decodeBase58WithChecksum()
                    transparentKey = decodedWif.copyOfRange(1, decodedWif.size)
                }*/

                val usk = DerivationTool.getInstance().deriveUnifiedSpendingKey(transparentKey, extendedSecretKey, seedPhrase, wallet.network, Account(0))

                // zero our intermediate variables
                seedPhrase.fill(0)
                extendedSecretKey.fill(0)
                transparentKey.fill(0)

                val internalId =
                    wallet.sendToAddress(
                        usk,
                        Zatoshi(zatoshi.toLong()),
                        toAddress,
                        memo,
                    )
                val tx = wallet.coroutineScope.async { wallet.transactions.first().first() }.await()
                val map = Arguments.createMap()
                map.putString("txid", tx.rawId.byteArray.toHexReversed())
                if (tx.raw != null) map.putString("raw", tx.raw?.byteArray?.toHex())
                promise.resolve(map)
            } catch (t: Throwable) {
                promise.reject("Err", t)
            }
        }
    }

    @ReactMethod
    fun shieldFunds(
        alias: String,
        seed: String,
        wif: String,
        memo: String,
        threshold: String,
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            try {
                val transparentKey: ByteArray
                val extsk = byteArrayOf()
                if (!wif.isNullOrEmpty()) {
                    val decodedWif = wif.decodeBase58WithChecksum()
                    transparentKey = decodedWif.copyOfRange(1, decodedWif.size)
                } else {
                    transparentKey = byteArrayOf()
                }
                val seedPhrase = SeedPhrase.new(seed)
                val usk = DerivationTool.getInstance().deriveUnifiedSpendingKey(transparentKey, extsk, seedPhrase.toByteArray(), wallet.network, Account.DEFAULT)
                val internalId =
                    wallet.shieldFunds(
                        usk,
                        memo,
                    )

                //TODO: if we use this function eventually, we should zeroize our intermediate variables here too

                val tx = wallet.coroutineScope.async { wallet.transactions.first().first() }.await()
                val parsedTx = parseTx(wallet, tx)

                // Hack: Memos aren't ready to be queried right after broadcast
                val memos = Arguments.createArray()
                memos.pushString(memo)
                parsedTx.putArray("memos", memos)
                promise.resolve(parsedTx)
            } catch (t: Throwable) {
                promise.reject("Err", t)
            }
        }
    }

    @ReactMethod
    fun stopAndDeleteWallet(
        alias: String,
        promise: Promise
    ) {
          moduleScope.launch {
              try {
                  val wallet = getWallet(alias)
                  wallet.close()
                  synchronizerMap.remove(alias)
                  val network = "VRSC"
                  val result = Synchronizer.erase(reactApplicationContext, networks.getOrDefault(network, ZcashNetwork.Mainnet), alias)
                  promise.resolve(result)
              } catch (e: Exception) {
                  promise.reject("CLEAR_ERROR", "Failed to clear Rust backend", e)
              }
          }
    }

    //
    // AddressTool
    //

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    @ReactMethod
    fun bech32Decode(
        bech32Key: String,
        promise: Promise,
    ) {
        moduleScope.launch {
            try {
                val keyBytes = decodeSaplingSpendKey(bech32Key)
                val result = keyBytes.toHexString()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("DECODE_ERROR","Failed to decode bech32 spendkey", e)
            }
        }
    }

    //
    // AddressTool
    //

    @OptIn(kotlin.ExperimentalStdlibApi::class)
    @ReactMethod
    fun deterministicSeedBytes(
        seed: String,
        promise: Promise,
    ) {
        moduleScope.launch {
            try {
                val keyBytes = SeedPhrase.new(seed).toByteArray()
                val result = keyBytes.toHexString()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("SEED_ERROR","Failed to convert mnemonicSeed to bytes", e)
            }
        }
    }

    //
    // AddressTool
    // State-based, fetches Unified address from synchronizer instance
    //

    @ReactMethod
    fun getUnifiedAddress(
        alias: String,
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            promise.wrap {
                val unifiedAddress = wallet.getUnifiedAddress(Account(0))
                val saplingAddress = wallet.getSaplingAddress(Account(0))
                val transparentAddress = wallet.getTransparentAddress(Account(0))

                val map = Arguments.createMap()
                map.putString("unifiedAddress", unifiedAddress)
                map.putString("saplingAddress", saplingAddress)
                map.putString("transparentAddress", transparentAddress)
                return@wrap map
            }
        }
    }

    //
    // AddressTool
    // State-based, fetches Unified address from synchronizer instance
    //

    @ReactMethod
    fun getSaplingAddress(
        alias: String,
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            promise.wrap {
                val saplingAddress = wallet.getSaplingAddress(Account(0))

                return@wrap saplingAddress
            }
        }
    }

    //
    // AddressTool
    // Stateless, derives Address from viewing key
    //

    @ReactMethod
    fun deriveShieldedAddress(
        extsk: String,
        seed: String,
        network: String = "VRSC",
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                var seedPhrase = byteArrayOf()
                var extendedSecretKey = byteArrayOf()
                if (!seed.isNullOrEmpty()){
                    seedPhrase = SeedPhrase.new(seed).toByteArray()
                }
                if (!extsk.isNullOrEmpty()){
                    extendedSecretKey = SeedPhrase.new(extsk).toByteArray()
                }
                val spendingKey =
                    DerivationTool.getInstance().deriveUnifiedSpendingKey(
                        byteArrayOf(),
                        extendedSecretKey,
                        seedPhrase,
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account.DEFAULT,
                    )

                // zero our intermediate variables
                seedPhrase.fill(0)
                extendedSecretKey.fill(0)

                val viewingKey =
                    DerivationTool.getInstance().deriveUnifiedFullViewingKey(
                        spendingKey,
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                    )
                val shieldedAddress =
                    DerivationTool.getInstance().deriveShieldedAddress(
                        viewingKey.encoding,
                        networks.getOrDefault(network, ZcashNetwork.Mainnet)
                    )
                //Log.w("ReactNative", "spendingKey = " + spendingKey.copyBytes().toHexString())
                //Log.w("ReactNative", "viewingKey: " + viewingKey.encoding);
                //Log.w("ReactNative", "shieldedAddress: " + shieldedAddress);
                return@wrap shieldedAddress
            }
        }
    }

    //
    // AddressTool
    //

    @ReactMethod
    fun isValidAddress(
        address: String,
        network: String,
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                val isValid = DerivationTool.getInstance().isValidShieldedAddress(
                    address,
                    networks.getOrDefault(network, ZcashNetwork.Mainnet),
                )
                return@wrap isValid
            }
        }
    }

    //
    // Utilities
    //

    /**
     * Retrieve wallet object from synchronizer map
     */
    private fun getWallet(alias: String): SdkSynchronizer {
        return synchronizerMap[alias] ?: throw Exception("Wallet not found")
    }

    /**
     * Wrap the given block of logic in a promise, rejecting for any error.
     */
    private inline fun <T> Promise.wrap(block: () -> T) {
        try {
            resolve(block())
        } catch (t: Throwable) {
            reject("Err", t)
        }
    }

    private fun sendEvent(
        eventName: String,
        putArgs: (WritableMap) -> Unit,
    ) {
        val args = Arguments.createMap()
        putArgs(args)
        reactApplicationContext
            .getJSModule(RCTDeviceEventEmitter::class.java)
            .emit(eventName, args)
    }

    private fun ByteArray.toHexReversed(): String {
        val sb = StringBuilder(size * 2)
        var i = size - 1
        while (i >= 0)
            sb.append(String.format("%02x", this[i--]))
        return sb.toString()
    }

    data class Balances(
        val transparentBalance: Zatoshi?,
        val saplingBalances: WalletBalance?,
        /*val orchardBalances: WalletBalance?,*/
    )

    private fun decodeSaplingSpendKey(bech32Key: String): ByteArray {
        val (hrp, data, encoding) = Bech32.decode(bech32Key)

        require(hrp == "secret-extended-key-main"/* || hrp == "secret-extended-key-test"*/) {
            throw Exception("Invalid HRP: $hrp")
        }

        val bytes = Bech32.five2eight(data, offset = 0)

        require(bytes.size == 169) {
            throw Exception("Unexpected decoded key length: ${bytes.size} bytes")
        }

       return bytes
    }
}
