export type Network = 'mainnet' | 'testnet' | 'VRSC'

export interface InitializerConfig {
  networkName: string
  defaultHost: string
  defaultPort: number
  mnemonicSeed?: string
  extsk?: string
  wif?: string
  alias: string
  birthdayHeight: number
  newWallet: boolean
}

export interface SpendInfo {
  zatoshi: string
  toAddress: string
  memo?: string
  extsk?: string
  mnemonicSeed?: string
}

export interface ShieldFundsInfo {
  seed: string
  memo: string
  threshold: string
}

export interface SpendSuccess {
  txid: string
  raw?: string
}

export interface SpendFailure {
  errorMessage?: string
  errorCode?: string
}

export interface UnifiedViewingKey {
  extfvk: string
  extpub: string
}

export interface UnifiedSpendingKey {
  account: string
  usk: string
}

export interface BalanceEvent {
  transparentAvailableZatoshi: string
  transparentTotalZatoshi: string
  saplingAvailableZatoshi: string
  saplingTotalZatoshi: string

  /** @deprecated */
  availableZatoshi: string
  totalZatoshi: string
}

export interface StatusEvent {
  alias: string
  name:
    | 'STOPPED' /** Indicates that [stop] has been called on this Synchronizer and it will no longer be used. */
    | 'DISCONNECTED' /** Indicates that this Synchronizer is disconnected from its lightwalletd server. When set, a UI element may want to turn red. */
    | 'SYNCING' /** Indicates that this Synchronizer is actively downloading and scanning new blocks */
    | 'SYNCED' /** Indicates that this Synchronizer is fully up to date and ready for all wallet functions. When set, a UI element may want to turn green. In this state, the balance can be trusted. */
}

export interface TransactionEvent {
  transactions: Transaction[]
}

export interface UpdateEvent {
  alias: string
  scanProgress: number // 0 - 100
  networkBlockHeight: number
}

export interface SynchronizerCallbacks {
  onBalanceChanged(balance: BalanceEvent): void
  onStatusChanged(status: StatusEvent): void
  onTransactionsChanged(transactions: TransactionEvent): void
  onUpdate(event: UpdateEvent): void
}

export interface BlockRange {
  first: number
  last: number
}

export interface Transaction {
  rawTransactionId: string
  raw?: string
  blockTimeInSeconds: number
  minedHeight: number
  value: string
  fee?: string
  toAddress?: string
  memos: string[]
}

/** @deprecated Renamed `Transaction` because the package can now return unconfirmed shielding transactions */
export type ConfirmedTransaction = Transaction

export interface Addresses {
  unifiedAddress: string
  saplingAddress: string
  transparentAddress: string
}

export interface InfoResponse {
  percent: number
  longestchain: number
  blocks: number
  status: string
}

export interface PrivateBalanceResponse {
  confirmed: string
  total: string
  pending: string
}

export interface PrivateTransactionsResponse {
  transactions: Transaction[]
}


export interface ChannelKeys{

    /** The public Sapling z-address for this channel (bech32 format). */
    address: string

    /** The bech32-encoded Extended Full Viewing Key. */
    fvk: string

    /** The hex-encoded Extended Full Viewing Key (169 bytes). */
    fvkHex: string

    /** 🔑 The hex-encoded Diversifiable Full Viewing Key (128 bytes). This is required for decryption. */
    dfvkHex: string

    /** The hex-encoded Incoming Viewing Key. */
    ivk?: string

    /** The optional bech32-encoded spending key. */
    spendingKey?: string
}


export interface EncryptedPayload {
  
    /* The hex encoded ephemeral public key from the sender*/

    ephemeralPublicKey: string;

    /* The hex encoded ciphertext of the message */
    ciphertext: string;

    /* Optional hex encoded symmetric key, only returned if requested */
    symmetricKey?: string;
}