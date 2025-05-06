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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log
import java.lang.Error

@OptIn(kotlin.ExperimentalStdlibApi::class)
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
        birthdayHeight: Int,
        alias: String,
        networkName: String = "VRSC",
        defaultHost: String = "lwdlegacy.blur.cash",
        defaultPort: Int = 443,
        newWallet: Boolean,
        promise: Promise,
    ) = moduleScope.launch {
        //Log.w("ReactNative", "initializer, before promise");
        promise.wrap {
            //Log.w("ReactNative", "Initializer, start func")
            val network = networks.getOrDefault(networkName, ZcashNetwork.Mainnet)
            val endpoint = LightWalletEndpoint(defaultHost, defaultPort, true)
            val seedPhrase = SeedPhrase.new(seed)
            val transparentKey: ByteArray
            if (!wif.isNullOrEmpty()) {
                val decodedWif = wif.decodeBase58WithChecksum()
                transparentKey = decodedWif.copyOfRange(1, decodedWif.size)
            } else {
                transparentKey = byteArrayOf()
            }
            //Log.w("ReactNative", "Initializer bp1");
            val initMode = if (newWallet) WalletInitMode.NewWallet else WalletInitMode.ExistingWallet
            if (!synchronizerMap.containsKey(alias)) {
                synchronizerMap[alias] =
                    Synchronizer.new(
                        reactApplicationContext,
                        network,
                        alias,
                        endpoint,
                        seedPhrase.toByteArray(),
                        BlockHeight.new(network, birthdayHeight.toLong()),
                        initMode,
                        transparentKey,
                    ) as SdkSynchronizer
            }
            //Log.w("ReactNative", "Initializer bp2");
            val wallet = getWallet(alias)
            val scope = wallet.coroutineScope
            combine(wallet.progress, wallet.networkHeight) { progress, networkHeight ->
                return@combine mapOf("progress" to progress, "networkHeight" to networkHeight)
            }.collectWith(scope) { map ->
                val progress = map["progress"] as PercentDecimal
                var networkBlockHeight = map["networkHeight"] as BlockHeight?
                if (networkBlockHeight == null) networkBlockHeight = BlockHeight.new(wallet.network, birthdayHeight.toLong())

                sendEvent("UpdateEvent") { args ->
                    args.putString("alias", alias)
                    args.putInt(
                        "scanProgress",
                        progress.toPercentage(),
                    )
                    args.putInt("networkBlockHeight", networkBlockHeight.value.toInt())
                }
            }
            wallet.status.collectWith(scope) { status ->
                sendEvent("StatusEvent") { args ->
                    args.putString("alias", alias)
                    args.putString("name", status.toString())
                }
            }
            //Log.w("ReactNative", "Initializer bp3");
            wallet.transactions.collectWith(scope) { txList ->
                scope.launch {
                    val nativeArray = Arguments.createArray()
                    txList.filter { tx -> tx.transactionState != TransactionState.Expired }.map { tx ->
                        launch {
                            val parsedTx = parseTx(wallet, tx)
                            nativeArray.pushMap(parsedTx)
                        }
                    }.forEach { it.join() }

                    sendEvent("TransactionEvent") { args ->
                        args.putString("alias", alias)
                        args.putArray(
                            "transactions",
                            nativeArray,
                        )
                    }
                }
            }
            //Log.w("ReactNative", "Initializer bp4");
            combine(
                wallet.transparentBalance,
                wallet.saplingBalances,
                /*wallet.orchardBalances,*/
            ) { transparentBalance: Zatoshi?, saplingBalances: WalletBalance? /*, orchardBalances: WalletBalance?*/ ->
                return@combine Balances(
                    transparentBalance = transparentBalance,
                    saplingBalances = saplingBalances,
                    /*orchardBalances = orchardBalances,*/
                )
            }.collectWith(scope) { map ->
                val transparentBalance = map.transparentBalance
                val saplingBalances = map.saplingBalances
                /*val orchardBalances = map.orchardBalances*/

                val transparentAvailableZatoshi = transparentBalance ?: Zatoshi(0L)
                val transparentTotalZatoshi = transparentBalance ?: Zatoshi(0L)

                val saplingAvailableZatoshi = saplingBalances?.available ?: Zatoshi(0L)
                val saplingTotalZatoshi = saplingBalances?.total ?: Zatoshi(0L)

                /*val orchardAvailableZatoshi = orchardBalances?.available ?: Zatoshi(0L)
                val orchardTotalZatoshi = orchardBalances?.total ?: Zatoshi(0L)*/

                //Log.w("ReactNative", "Initializer bp5");

                sendEvent("BalanceEvent") { args ->
                    args.putString("alias", alias)
                    args.putString("transparentAvailableZatoshi", transparentAvailableZatoshi.value.toString())
                    args.putString("transparentTotalZatoshi", transparentTotalZatoshi.value.toString())
                    args.putString("saplingAvailableZatoshi", saplingAvailableZatoshi.value.toString())
                    args.putString("saplingTotalZatoshi", saplingTotalZatoshi.value.toString())
                    /*
                    args.putString("orchardAvailableZatoshi", orchardAvailableZatoshi.value.toString())
                    args.putString("orchardTotalZatoshi", orchardTotalZatoshi.value.toString())
                    */
                }
            }
            return@wrap null
        }
    }

    @ReactMethod
    fun getInfo(alias: String, promise: Promise) {
        //Log.w("ReactNative", "getInfo called")
        val wallet = getWallet(alias)
        val scope = wallet.coroutineScope

        val birthdayHeight = wallet.latestBirthdayHeight;

        val latestHeight: BlockHeight = wallet.latestHeight ?: BlockHeight.new(wallet.network, birthdayHeight.value)

        scope.launch {
            try {
                val map = combine(
                    wallet.processorInfo,
                    wallet.progress,
                    wallet.networkHeight,
                    wallet.status
                ) { processorInfo, progress, networkHeight, status ->
                    mapOf(
                        "processorInfo" to processorInfo,
                        "progress" to progress,
                        "networkHeight" to (networkHeight ?: BlockHeight.new(wallet.network, birthdayHeight.value)),
                        "status" to status
                    )
                }.first()

                val processorInfo = map["processorInfo"] as CompactBlockProcessor.ProcessorInfo
                val processorNetworkHeight = processorInfo.networkBlockHeight?: BlockHeight.new(wallet.network, birthdayHeight.value)
                val firstUnenhancedHeight = processorInfo.firstUnenhancedHeight?: BlockHeight.new(wallet.network, birthdayHeight.value)
                val processorScannedHeight = processorInfo.lastScannedHeight?: BlockHeight.new(wallet.network, birthdayHeight.value)
                val progress = map["progress"] as PercentDecimal
                val networkBlockHeight = map["networkHeight"] as BlockHeight
                val status = map["status"]

                Log.i("ReactNative", "processorInfo: networkHeight(${processorNetworkHeight.value})")
                Log.i("ReactNative", "processorInfo: overallSyncRange(${processorInfo.overallSyncRange})")
                Log.i("ReactNative", "processorInfo: lastScannedHeight(${processorScannedHeight.value})")
                Log.i("ReactNative", "processorInfo: firstUnenhancedHeight(${firstUnenhancedHeight.value})")
                Log.w("ReactNative", "progress.toPercentage(): ${progress.toPercentage()}")
                Log.w("ReactNative", "networkBlockHeight: ${networkBlockHeight.value.toInt()}")
                Log.w("ReactNative", "latestBlockHeight: ${latestHeight.value.toInt()}")
                Log.w("ReactNative", "wallet status: ${status.toString().lowercase()}")

                val resultMap = Arguments.createMap().apply {
                    putInt("percent", progress.toPercentage())
                    putInt("longestchain", networkBlockHeight.value.toInt())
                    putString("status", status.toString().lowercase())
                    putInt("blocks", processorScannedHeight.value.toInt())
                }

                promise.resolve(resultMap)

            } catch (e: Exception) {
                Log.e("ReactNative", "getInfo failed", e)
                promise.reject("GET_INFO_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getPrivateBalance(alias: String, promise: Promise) {
        //Log.w("ReactNative", "getPrivateBalance called")
        val wallet = getWallet(alias)
        val scope = wallet.coroutineScope

        scope.launch {
            try {
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

            } catch (e: Exception) {
                Log.e("ReactNative", "getPrivateBalance failed", e)
                promise.reject("GET_PRIVATE_BALANCE_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getPrivateTransactions(alias: String, promise: Promise) {
        //Log.w("ReactNative", "getPrivateTransactions called")
        val wallet = getWallet(alias)
        val scope = wallet.coroutineScope

        try {
            scope.launch {
                try {
                    val txList = wallet.transactions.first()
                    val nativeArray = Arguments.createArray()

                    for (tx in txList) {
                        if (tx.transactionState != TransactionState.Expired) {
                            try {
                                val parsedTx = parseTx(wallet, tx)
                                //Log.d("ReactNative", "Parsed TX Map: ${parsedTx.toString()}")

                                nativeArray.pushMap(parsedTx)
                            } catch (t: Throwable) {
                                // It's okay if recipient can't be fetched (e.g. shielding tx) )
                            }
                        }
                    }

                    //Log.e("ReactNative", "transactionsArray: $nativeArray")

                    val result = Arguments.createMap().apply {
                        putArray("transactions", nativeArray)
                    }

                    promise.resolve(result)
                } catch (e: Exception) {
                    Log.e("ReactNative", "Error while collecting transactions", e)
                    promise.reject("GET_PRIVATE_TRANSACTIONS_FAILED", e.message, e)
                }
            }
        } catch (e: Exception) {
            Log.e("ReactNative", "getPrivateTransactions failed", e)
            promise.reject("GET_PRIVATE_TRANSACTIONS_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun stop(
        alias: String,
        promise: Promise,
    ) {
        promise.wrap {
            val wallet = getWallet(alias)
            wallet.close()
            synchronizerMap.remove(alias)
            return@wrap null
        }
    }

    //TODO: consider adding boolean argument to conditionally include rawTxData
    private suspend fun parseTx(
        wallet: SdkSynchronizer,
        tx: TransactionOverview,
    ): WritableMap {
        //Log.e("ReactNative", "parseTx called!")
        val map = Arguments.createMap()
        try {
            //Log.d("ReactNative", "TransactionOverview: " + tx.toString())
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
                //TODO: probably a more graceful way to handle "address" above
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
        seed: String,
        network: String = "VRSC",
        promise: Promise,
    ) {
        //Log.d("ReactNative", "deriveViewingKey called!!")
        moduleScope.launch {
            promise.wrap {
                val seedPhrase = SeedPhrase.new(seed)
                val spendingKey =
                    DerivationTool.getInstance().deriveUnifiedSpendingKey(
                        byteArrayOf(),
                        seedPhrase.toByteArray(),
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account.DEFAULT,
                    )
                //Log.w("ReactNative", spendingKey.copyBytes().toHexString())
                val keys =
                    DerivationTool.getInstance().deriveUnifiedFullViewingKey(
                        spendingKey,
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                    )
                //Log.i("ReactNative", "keys: " + keys.encoding);
                return@wrap keys.encoding
            }
        }
    }


    @ReactMethod
    fun deriveSaplingSpendingKey(
        seed: String,
        network: String,
        promise: Promise,
    ) {
        //Log.d("ReactNative", "deriveShieldedSpendingKeyCalled!");
        moduleScope.launch {
            promise.wrap {
                val seedPhrase = SeedPhrase.new(seed)
                val key =
                    DerivationTool.getInstance().deriveSaplingSpendingKey(
                        seedPhrase.toByteArray(),
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account.DEFAULT,
                    )
                //Log.w("ReactNative", "seed: " + seed);

                //Log.i("ReactNative", "key: " + key.copyBytes().toHexString());
                return@wrap key.copyBytes().toHexString()
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
        //wif: String,
        seed: String,
        promise: Promise,
    ) {
        val wallet = getWallet(alias)
        //Log.i("ReactNative", "sendToAddress called!");
        wallet.coroutineScope.launch {
            try {
                val transparentKey = byteArrayOf()
                /*val transparentKey: ByteArray
                if (!wif.isNullOrEmpty()) {
                    val decodedWif = wif.decodeBase58WithChecksum()
                    transparentKey = decodedWif.copyOfRange(1, decodedWif.size)
                } else {
                    transparentKey = byteArrayOf()
                }*/
                val seedPhrase = SeedPhrase.new(seed)
                val usk = DerivationTool.getInstance().deriveUnifiedSpendingKey(transparentKey, seedPhrase.toByteArray(), wallet.network, Account(0))
                val internalId =
                    wallet.sendToAddress(
                        usk,
                        Zatoshi(zatoshi.toLong()),
                        toAddress,
                        memo,
                    )
                val tx = wallet.coroutineScope.async { wallet.transactions.first().first() }.await()
                val map = Arguments.createMap()
                //Log.i("ReactNative", "sendToAddress: txid(${tx.rawId.byteArray.toHexReversed()}");
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
                if (!wif.isNullOrEmpty()) {
                    val decodedWif = wif.decodeBase58WithChecksum()
                    transparentKey = decodedWif.copyOfRange(1, decodedWif.size)
                } else {
                    transparentKey = byteArrayOf()
                }
                val seedPhrase = SeedPhrase.new(seed)
                val usk = DerivationTool.getInstance().deriveUnifiedSpendingKey(transparentKey, seedPhrase.toByteArray(), wallet.network, Account.DEFAULT)
                val internalId =
                    wallet.shieldFunds(
                        usk,
                        memo,
                    )
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

    //
    // AddressTool
    //

    @ReactMethod
    fun deriveUnifiedAddress(
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
    //

    @ReactMethod
    fun deriveSaplingAddress(
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
    //
    @ReactMethod
    fun deriveShieldedAddressFromSeed(
        seed: String,
        network: String = "VRSC",
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                val seedPhrase = SeedPhrase.new(seed)
                val shieldedAddress =
                    DerivationTool.getInstance().deriveShieldedAddress(
                        seedPhrase.toByteArray(),
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account(1)
                    )
                Log.w("ReactNative", "shieldedAddress: " + shieldedAddress);
                return@wrap shieldedAddress
            }
        }
    }

    //
    // AddressTool
    //
    @ReactMethod
    fun deriveShieldedAddress(
        seed: String,
        network: String = "VRSC",
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                val seedPhrase = SeedPhrase.new(seed)
                val spendingKey =
                    DerivationTool.getInstance().deriveUnifiedSpendingKey(
                        byteArrayOf(),
                        seedPhrase.toByteArray(),
                        networks.getOrDefault(network, ZcashNetwork.Mainnet),
                        Account.DEFAULT,
                    )
                //Log.w("ReactNative", spendingKey.copyBytes().toHexString())
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

    @ReactMethod
    fun isValidAddress(
        address: String,
        network: String,
        promise: Promise,
    ) {
        moduleScope.launch {
            promise.wrap {
                var isValid = false
                val wallets = synchronizerMap.asIterable()
                for (wallet in wallets) {
                    if (wallet.value.network.networkName == network) {
                        isValid = wallet.value.isValidAddress(address)
                        break
                    }
                }
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
}
