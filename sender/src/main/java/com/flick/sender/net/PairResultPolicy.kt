package com.flick.sender.net

/** Pair-code erasure is driven by whether the single-use code may have reached the receiver. */
internal object PairResultPolicy {
    fun clearCode(result: ControlClient.Result): Boolean = when (result) {
        ControlClient.Result.Denied, ControlClient.Result.Busy -> true
        is ControlClient.Result.PairedBusy -> true
        is ControlClient.Result.Unreachable -> result.pairCodeSent
        is ControlClient.Result.ProtocolError -> result.pairCodeSent
        else -> false
    }
}
