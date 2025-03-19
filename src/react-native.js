import { add } from 'biggystring';
import {
  EventSubscription,
  NativeEventEmitter,
  NativeModules
} from 'react-native';

const { VerusLightClient } = NativeModules;

const Tools = {
  deriveViewingKey: async (seedBytesHex, network = 'VRSC') => {
    console.log("deriveShieldedViewingkey called!");
    const result = await VerusLightClient.deriveViewingKey(seedBytesHex, network);
    return result;
  },
  
  deriveSaplingSpendingKey: async (seedBytesHex, network = 'VRSC') => {
    console.log("deriveShieldedSpendkey called!");
    const result = await VerusLightClient.deriveSaplingSpendingKey(seedBytesHex, network);
    return result;
  },
  
  deriveShieldedAddress: async (seedBytesHex, network = 'VRSC') => {
    console.log("deriveShieldedAddress called!");
    const result = await VerusLightClient.deriveShieldedAddress(seedBytesHex, network);
    return result;
  },
  
  deriveShieldedAddressFromSeed: async (seedBytesHex, network = 'VRSC') => {
    console.log("deriveShieldedAddressFromSeed called!");
    const result = await VerusLightClient.deriveShieldedAddressFromSeed(seedBytesHex, network);
    return result;
  },
  
  getBirthdayHeight: async (host, port) => {
    const result = await VerusLightClient.getBirthdayHeight(host, port);
    return result;
  },
  
  isValidAddress: async (address, network = 'VRSC') => {
    const result = await VerusLightClient.isValidAddress(address, network);
    return result;
  }
};

class Synchronizer {
  constructor(alias, network) {
    this.eventEmitter = new NativeEventEmitter(); // VerusLightClient is not needed here
    this.subscriptions = [];
    this.alias = alias;
    this.network = network;
  }

  async stop() {
    this.unsubscribe();
    const result = await VerusLightClient.stop(this.alias);
    return result;
  }

  async initialize(initializerConfig) {
    console.warn("within initialize func, before await");
    console.warn("mnemonicSeed: " + initializerConfig.mnemonicSeed);
    console.warn("wif: " + initializerConfig.wif);
    console.warn("birthday: " + initializerConfig.birthdayHeight);
    console.warn("alias: " + initializerConfig.alias);
    console.warn("networkName: " + initializerConfig.networkName);
    console.warn("host: " + initializerConfig.defaultHost);
    console.warn("port: " + initializerConfig.defaultPort);
    console.warn("newWallet: " + initializerConfig.newWallet);

    await VerusLightClient.initialize(
      initializerConfig.mnemonicSeed,
      initializerConfig.wif,
      initializerConfig.birthdayHeight,
      initializerConfig.alias,
      initializerConfig.networkName,
      initializerConfig.defaultHost,
      initializerConfig.defaultPort,
      initializerConfig.newWallet
    );
    console.warn("within initialize func, after await");
  }

  async deriveUnifiedAddress() {
    const result = await VerusLightClient.deriveUnifiedAddress(this.alias);
    return result;
  }

  async deriveSaplingAddress() {
    const result = await VerusLightClient.deriveSaplingAddress(this.alias);
    return result;
  }

  async deriveShieledAddress() {
    const result = await VerusLightClient.deriveShieldedAddress(this.alias);
    return result;
  }

  async getLatestNetworkHeight(alias) {
    const result = await VerusLightClient.getLatestNetworkHeight(alias);
    return result;
  }

  async rescan() {
    await VerusLightClient.rescan(this.alias);
  }

  async sendToAddress(spendInfo) {
    const result = await VerusLightClient.sendToAddress(
      this.alias,
      spendInfo.zatoshi,
      spendInfo.toAddress,
      spendInfo.memo,
      spendInfo.mnemonicSeed
    );
    return result;
  }

  async shieldFunds(shieldFundsInfo) {
    const result = await VerusLightClient.shieldFunds(
      this.alias,
      shieldFundsInfo.seed,
      shieldFundsInfo.memo,
      shieldFundsInfo.threshold
    );
    return result;
  }

  // Events
  subscribe({ onBalanceChanged, onStatusChanged, onTransactionsChanged, onUpdate }) {
    this.setListener('BalanceEvent', (event) => {
      const {
        transparentAvailableZatoshi,
        transparentTotalZatoshi,
        saplingAvailableZatoshi,
        saplingTotalZatoshi
      } = event;

      event.availableZatoshi = add(
        transparentAvailableZatoshi,
        saplingAvailableZatoshi
      );
      event.totalZatoshi = add(transparentTotalZatoshi, saplingTotalZatoshi);
      onBalanceChanged(event);
    });
    this.setListener('StatusEvent', onStatusChanged);
    this.setListener('TransactionEvent', onTransactionsChanged);
    this.setListener('UpdateEvent', onUpdate);
  }

  setListener(eventName, callback = (t) => null) {
    this.subscriptions.push(
      this.eventEmitter.addListener(eventName, (arg) =>
        arg.alias === this.alias ? callback(arg) : null
      )
    );
  }

  unsubscribe() {
    this.subscriptions.forEach((subscription) => {
      subscription.remove();
    });
  }
}

const makeSynchronizer = async (initializerConfig) => {
  console.warn("before constructor in makeSynchronizer");
  const synchronizer = new Synchronizer(
    initializerConfig.alias,
    initializerConfig.networkName
  );
  console.warn("before synchronizer.initialize()");
  await synchronizer.initialize(initializerConfig);
  console.warn("before return synchronizer");
  return synchronizer;
};

export { Tools, Synchronizer, makeSynchronizer };
