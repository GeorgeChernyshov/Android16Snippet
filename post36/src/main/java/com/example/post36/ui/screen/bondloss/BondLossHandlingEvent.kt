package com.example.post36.ui.screen.bondloss

sealed class BondLossHandlingEvent {
    class RequestPermissions(
        val permissions: List<String>
    ) : BondLossHandlingEvent()
}