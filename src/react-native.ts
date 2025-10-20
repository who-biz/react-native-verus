import { add } from 'biggystring'
import {
  EventSubscription,
  NativeEventEmitter,
  NativeModules
} from 'react-native'

import {
  Addresses,
  InfoResponse,
  InitializerConfig,
  Network,
  PrivateBalanceResponse,
  PrivateTransactionsResponse,
  ShieldFundsInfo,
  SpendFailure,
  SpendInfo,
  SpendSuccess,
  SynchronizerCallbacks,
  Transaction,
  UnifiedViewingKey,
  UnifiedSpendingKey,
  ChannelKeys,
  EncryptedPayload
} from './types'
export * from './types'

const { VerusLightClient } = NativeModules

type Callback = (...args: any[]) => any

let synchronizerInstance: Synchronizer;

export const Tools = {
  bech32Decode: async (
    bech32Key: string
  ): Promise<String> => {
    const result = await VerusLightClient.bech32Decode(bech32Key)
    return result
  },
  deterministicSeedBytes: async (
    seed: string
  ): Promise<String> => {
    const result = await VerusLightClient.deterministicSeedBytes(seed);
    return result
  },
  deriveViewingKey: async (
    extsk?: string,
    seedBytesHex?: string,
    network: Network = 'VRSC'
  ): Promise<UnifiedViewingKey> => {
    const result = await VerusLightClient.deriveViewingKey(extsk, seedBytesHex, network)
    return result
  },
  deriveSaplingSpendingKey: async (
    seedBytesHex: string,
    network: Network = 'VRSC'
  ): Promise<UnifiedSpendingKey> => {
    const result = await VerusLightClient.deriveSaplingSpendingKey(seedBytesHex, network)
    return result
  },
  deriveShieldedAddress: async (
    extsk?: string,
    seedBytesHex?: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    const result = await VerusLightClient.deriveShieldedAddress(extsk, seedBytesHex, network)
    return result
  },
  getBirthdayHeight: async (host: string, port: number): Promise<number> => {
    const result = await VerusLightClient.getBirthdayHeight(host, port)
    return result
  },
  isValidAddress: async (
    address: string,
    network: Network = 'VRSC'
  ): Promise<boolean> => {
    const result = await VerusLightClient.isValidAddress(address, network)
    return result
  },
  getSymmetricKey: async (
    ufvk: string,
    ephemeralPublicKeyHex: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    console.warn("getSymmetricKey called!, ufvk(" + ufvk + "), epkHex(" + ephemeralPublicKeyHex + ")"); 
    const result = await VerusLightClient.getSymmetricKey(ufvk, ephemeralPublicKeyHex, network)
    console.warn("getSymmetricKey result: " + result.toString())
    return result
  },
  generateSymmetricKey: async (
    recipient: string,
    network: Network = 'VRSC'
  ): Promise<String> => {
    const result = await VerusLightClient.generateSymmetricKey(recipient, network)
    return result
  },


  /**
   * REPLACES the old zGetEncryptionAddress.
   * Derives a deterministic z-address for encrypted messaging between two parties.
   * @param {string | null} seed The user's wallet seed as a hex string. Can be null if spendingKey is provided.
   * @param {string | null} spendingKey The user's extended spending key. Can be null if seed is provided.
   * @param {string | null} fromId A unique identifier for the sender (e.g., a hex-encoded VerusID). Can be null.
   * @param {string | null} toId A unique identifier for the recipient (e.g., a hex-encoded VerusID). Can be null.
   * @param {number} hdIndex The HD account index to use if deriving from a seed. Defaults to 0.
   * @param {number} encryptionIndex The index for the final encryption key derivation. Defaults to 0.
   * @param {boolean} returnSecret If true, the derived extended spending key will be included in the result. Defaults to false.
   * @returns {Promise<{address: string, fullViewingKey: string, spendingKey?: string}>} A promise that resolves with a ChannelKeys object.
   */
  async getVerusEncryptionAddress(
    seed: string | null,
    spendingKey: string | null,
    fromId: string | null,
    toId: string | null,
    hdIndex: number = 0,
    encryptionIndex: number = 0,
    returnSecret: boolean = false
  ): Promise<ChannelKeys> {
    // Note: Make sure the 'ChannelKeys' type is defined in your types.ts file
    // export interface ChannelKeys { address: string; fullViewingKey: string; spendingKey?: string; }
    return VerusLightClient.zGetEncryptionAddress(
      seed,
      spendingKey,
      fromId,
      toId,
      hdIndex,
      encryptionIndex,
      returnSecret
    );
  },

  /**
   * ADDED function.
   * Encrypts a message for a given z-address.
   * @param {string} address The recipient's z-address.
   * @param {string} message The plaintext message to encrypt.
   * @param {boolean} returnSsk If true, the symmetric key used for encryption will be returned. Defaults to false.
   * @returns {Promise<{ephemeralPublicKey: string, ciphertext: string, symmetricKey?: string}>} A promise that resolves with an EncryptedPayload object.
   */
  async encryptVerusMessage(
    address: string,
    message: string,
    returnSsk: boolean = false
  ): Promise<EncryptedPayload> {
    // Note: Make sure the 'EncryptedPayload' type is defined in your types.ts file
    // export interface EncryptedPayload { ephemeralPublicKey: string; ciphertext: string; symmetricKey?: string; }
    return VerusLightClient.encryptVerusMessage(address, message, returnSsk);
  },

  /**
   * ADDED function.
   * Decrypts a Verus-specific encrypted message.
   * @param {string | null} fvkHex The recipient's hex-encoded full viewing key. Not needed if sskHex is provided.
   * @param {string | null} epkHex The sender's hex-encoded ephemeral public key. Not needed if sskHex is provided.
   * @param {string} ciphertextHex The hex-encoded encrypted message.
   * @param {string | null} sskHex The hex-encoded symmetric session key. If provided, fvkHex and epkHex are ignored.
   * @returns {Promise<string>} A promise that resolves with the decrypted plaintext message.
   */
   async decryptVerusMessage(
    fvkHex: string | null,
    epkHex: string | null,
    ciphertextHex: string,
    sskHex: string | null
  ): Promise<string> {
    return VerusLightClient.decryptVerusMessage(
      fvkHex,
      epkHex,
      ciphertextHex,
      sskHex
    );
  },
};

export class Synchronizer {
  eventEmitter: NativeEventEmitter
  subscriptions: EventSubscription[]
  alias: string
  network: string

  constructor(alias: string, network: string) {
    this.eventEmitter = new NativeEventEmitter(VerusLightClient)
    this.subscriptions = []
    this.alias = alias
    this.network = network
  }

  async stop(): Promise<string> {
    this.unsubscribe()
    const result = await VerusLightClient.stop(this.alias)
    return result
  }

  async initialize(initializerConfig: InitializerConfig): Promise<void> {
    //console.warn("within initialize func, before await")
    //console.warn("mnemonicSeed: " + initializerConfig.mnemonicSeed);
    //console.warn("wif: " + initializerConfig.wif);
    //console.warn("extsk: " + initializerConfig.extsk);
    //console.warn("birthday: " + initializerConfig.birthdayHeight);
    //console.warn("alias: " + initializerConfig.alias);
    //console.warn("networkName: " + initializerConfig.networkName);
    //console.warn("host: " + initializerConfig.defaultHost);
    //console.warn("port: " + initializerConfig.defaultPort);
    //console.warn("newWallet: " + initializerConfig.newWallet);

    await VerusLightClient.initialize(
      initializerConfig.mnemonicSeed,
      initializerConfig.wif,
      initializerConfig.extsk,
      initializerConfig.birthdayHeight,
      initializerConfig.alias,
      initializerConfig.networkName,
      initializerConfig.defaultHost,
      initializerConfig.defaultPort,
      initializerConfig.newWallet
    )
    //console.warn("within initialize func, after await")
  }

//  private static _instance = new Synchronizer(this.alias, this.network);

//  static get instance() {
//    return this._instance;
//  }

  async getInfo(): Promise<InfoResponse> {
    const result = await VerusLightClient.getInfo(this.alias)
    //console.log(JSON.stringify(result));
    return result
  }

  async getPrivateBalance(): Promise<PrivateBalanceResponse> {
    const result = await VerusLightClient.getPrivateBalance(this.alias)
    //console.log(JSON.stringify(result));
    return result
  }

  async getPrivateTransactions(): Promise<PrivateTransactionsResponse> {
    const result = await VerusLightClient.getPrivateTransactions(this.alias)
    //console.log(JSON.stringify(result));
    return result
  }

  async deriveUnifiedAddress(): Promise<Addresses> {
    const result = await VerusLightClient.deriveUnifiedAddress(this.alias)
    return result
  }

  async deriveSaplingAddress(): Promise<string> {
    const result = await VerusLightClient.deriveSaplingAddress(this.alias)
    return result
  }

  async deriveShieledAddress(): Promise<Addresses> {
    const result = await VerusLightClient.deriveShieldedAddress(this.alias)
    return result
  }

  async getLatestNetworkHeight(alias: string): Promise<number> {
    const result = await VerusLightClient.getLatestNetworkHeight(alias)
    return result
  }

  async rescan(): Promise<void> {
    await VerusLightClient.rescan(this.alias)
  }

  async sendToAddress(
    spendInfo: SpendInfo
  ): Promise<SpendSuccess | SpendFailure> {
    //console.warn("mnemonicSeed(" + spendInfo.mnemonicSeed + "), extsk(" + spendInfo.extsk + ")");
    const result = await VerusLightClient.sendToAddress(
      this.alias,
      spendInfo.zatoshi,
      spendInfo.toAddress,
      spendInfo.memo,
      spendInfo.extsk,
      spendInfo.mnemonicSeed
    )
    //console.warn("in sendToAddress, result.txid(" + result.txid + ")");
    return result
  }

  async shieldFunds(shieldFundsInfo: ShieldFundsInfo): Promise<Transaction> {
    const result = await VerusLightClient.shieldFunds(
      this.alias,
      shieldFundsInfo.seed,
      shieldFundsInfo.memo,
      shieldFundsInfo.threshold
    )
    return result
  }

  // Events

  subscribe({
    onBalanceChanged,
    onStatusChanged,
    onTransactionsChanged,
    onUpdate
  }: SynchronizerCallbacks): void {
    this.setListener('BalanceEvent', event => {
      const {
        transparentAvailableZatoshi,
        transparentTotalZatoshi,
        saplingAvailableZatoshi,
        saplingTotalZatoshi
      } = event

      event.availableZatoshi = add(
        transparentAvailableZatoshi,
        saplingAvailableZatoshi
      )
      event.totalZatoshi = add(transparentTotalZatoshi, saplingTotalZatoshi)
      onBalanceChanged(event)
    })
    this.setListener('StatusEvent', onStatusChanged)
    this.setListener('TransactionEvent', onTransactionsChanged)
    this.setListener('UpdateEvent', onUpdate)
  }

  private setListener<T>(
    eventName: string,
    callback: Callback = (t: any) => null
  ): void {
    this.subscriptions.push(
      this.eventEmitter.addListener(eventName, arg =>
        arg.alias === this.alias ? callback(arg) : null
      )
    )
  }

  unsubscribe(): void {
    this.subscriptions.forEach(subscription => {
      subscription.remove()
    })
  }
}

export const getSynchronizerInstance = (
  alias: string,
  network: string
): Synchronizer => {
  if (!synchronizerInstance) { 
    synchronizerInstance = new Synchronizer(alias, network);
  }
  return synchronizerInstance;
}

export const makeSynchronizer = async (
  initializerConfig: InitializerConfig
): Promise<Synchronizer> => {
  //console.warn("before getSynchronizerInstance in makeSynchronizer")
  getSynchronizerInstance(initializerConfig.alias, initializerConfig.networkName);
  //console.warn("before synchronizer.initialize() extsk(" + initializerConfig.extsk + ")")
  await synchronizerInstance.initialize(initializerConfig)
  //console.warn("before return synchronizer")
  return synchronizerInstance;
}

export const deleteWallet = async (
  alias: string, 
  network: string
): Promise<boolean> => {
   //console.warn("deleteWallet called in typescript! alias(" + alias + ")");
   const result = await VerusLightClient.deleteWallet(alias, network);
   return result;
}

//export const SdkSynchronizer = Synchronizer.instance;

/*export const getSaplingAddress = async (
  alias: string,
  networkName: string
): Promise<String> => {
  //console.warn("before calling Synchronizer.getSaplingAddress")
  const address = await Synchronizer.deriveSaplingAddress()
  //console.warn("before return saplingAddress: " + address)
  //return address
}*/
