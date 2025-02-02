package net.mullvad.mullvadvpn.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mullvad.mullvadvpn.compose.state.ConnectUiState
import net.mullvad.mullvadvpn.lib.model.ActionAfterDisconnect
import net.mullvad.mullvadvpn.lib.model.ConnectError
import net.mullvad.mullvadvpn.lib.model.DeviceState
import net.mullvad.mullvadvpn.lib.model.TunnelState
import net.mullvad.mullvadvpn.lib.model.WebsiteAuthToken
import net.mullvad.mullvadvpn.lib.shared.AccountRepository
import net.mullvad.mullvadvpn.lib.shared.ConnectionProxy
import net.mullvad.mullvadvpn.lib.shared.DeviceRepository
import net.mullvad.mullvadvpn.lib.shared.VpnPermissionRepository
import net.mullvad.mullvadvpn.repository.InAppNotificationController
import net.mullvad.mullvadvpn.usecase.LastKnownLocationUseCase
import net.mullvad.mullvadvpn.usecase.NewDeviceNotificationUseCase
import net.mullvad.mullvadvpn.usecase.OutOfTimeUseCase
import net.mullvad.mullvadvpn.usecase.PaymentUseCase
import net.mullvad.mullvadvpn.usecase.SelectedLocationTitleUseCase
import net.mullvad.mullvadvpn.util.combine
import net.mullvad.mullvadvpn.util.daysFromNow
import net.mullvad.mullvadvpn.util.toInAddress
import net.mullvad.mullvadvpn.util.toOutAddress

@Suppress("LongParameterList")
class ConnectViewModel(
    private val accountRepository: AccountRepository,
    private val deviceRepository: DeviceRepository,
    inAppNotificationController: InAppNotificationController,
    private val newDeviceNotificationUseCase: NewDeviceNotificationUseCase,
    selectedLocationTitleUseCase: SelectedLocationTitleUseCase,
    private val outOfTimeUseCase: OutOfTimeUseCase,
    private val paymentUseCase: PaymentUseCase,
    private val connectionProxy: ConnectionProxy,
    lastKnownLocationUseCase: LastKnownLocationUseCase,
    private val vpnPermissionRepository: VpnPermissionRepository,
    private val isPlayBuild: Boolean
) : ViewModel() {
    private val _uiSideEffect = Channel<UiSideEffect>()

    val uiSideEffect =
        merge(_uiSideEffect.receiveAsFlow(), outOfTimeEffect(), revokedDeviceEffect())

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<ConnectUiState> =
        combine(
                selectedLocationTitleUseCase.selectedLocationTitle(),
                inAppNotificationController.notifications,
                connectionProxy.tunnelState,
                lastKnownLocationUseCase.lastKnownDisconnectedLocation,
                accountRepository.accountData,
                deviceRepository.deviceState.map { it?.displayName() }
            ) {
                selectedRelayItemTitle,
                notifications,
                tunnelState,
                lastKnownDisconnectedLocation,
                accountData,
                deviceName ->
                ConnectUiState(
                    location =
                        when (tunnelState) {
                            is TunnelState.Disconnected ->
                                tunnelState.location() ?: lastKnownDisconnectedLocation
                            is TunnelState.Connecting -> tunnelState.location
                            is TunnelState.Connected -> tunnelState.location
                            is TunnelState.Disconnecting -> lastKnownDisconnectedLocation
                            is TunnelState.Error -> null
                        },
                    selectedRelayItemTitle = selectedRelayItemTitle,
                    tunnelState = tunnelState,
                    inAddress =
                        when (tunnelState) {
                            is TunnelState.Connected -> tunnelState.endpoint.toInAddress()
                            is TunnelState.Connecting -> tunnelState.endpoint?.toInAddress()
                            else -> null
                        },
                    outAddress = tunnelState.location()?.toOutAddress() ?: "",
                    showLocation =
                        when (tunnelState) {
                            is TunnelState.Disconnected -> true
                            is TunnelState.Disconnecting -> {
                                when (tunnelState.actionAfterDisconnect) {
                                    ActionAfterDisconnect.Nothing -> false
                                    ActionAfterDisconnect.Block -> true
                                    ActionAfterDisconnect.Reconnect -> false
                                }
                            }
                            is TunnelState.Connecting -> false
                            is TunnelState.Connected -> false
                            is TunnelState.Error -> true
                        },
                    inAppNotification = notifications.firstOrNull(),
                    deviceName = deviceName,
                    daysLeftUntilExpiry = accountData?.expiryDate?.daysFromNow(),
                    isPlayBuild = isPlayBuild,
                )
            }
            .debounce(UI_STATE_DEBOUNCE_DURATION_MILLIS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ConnectUiState.INITIAL)

    init {
        viewModelScope.launch {
            paymentUseCase.verifyPurchases {
                viewModelScope.launch { accountRepository.getAccountData() }
            }
        }
        viewModelScope.launch { deviceRepository.updateDevice() }
    }

    fun onDisconnectClick() {
        viewModelScope.launch { connectionProxy.disconnect() }
    }

    fun onReconnectClick() {
        viewModelScope.launch { connectionProxy.reconnect() }
    }

    fun onConnectClick() {
        viewModelScope.launch {
            connectionProxy.connect().onLeft { connectError ->
                when (connectError) {
                    ConnectError.NoVpnPermission -> _uiSideEffect.send(UiSideEffect.NoVpnPermission)
                    is ConnectError.Unknown -> {
                        _uiSideEffect.send(UiSideEffect.ConnectError.Generic)
                    }
                }
            }
        }
    }

    fun requestVpnPermissionResult(hasVpnPermission: Boolean) {
        viewModelScope.launch {
            if (hasVpnPermission) {
                connectionProxy.connect()
            } else {
                vpnPermissionRepository.getAlwaysOnVpnAppName()?.let {
                    _uiSideEffect.send(UiSideEffect.ConnectError.AlwaysOnVpn(it))
                } ?: _uiSideEffect.send(UiSideEffect.ConnectError.NoVpnPermission)
            }
        }
    }

    fun onCancelClick() {
        viewModelScope.launch { connectionProxy.disconnect() }
    }

    fun onManageAccountClick() {
        viewModelScope.launch {
            accountRepository.getWebsiteAuthToken()?.let { wwwAuthToken ->
                _uiSideEffect.send(UiSideEffect.OpenAccountManagementPageInBrowser(wwwAuthToken))
            }
        }
    }

    fun dismissNewDeviceNotification() {
        newDeviceNotificationUseCase.clearNewDeviceCreatedNotification()
    }

    private fun outOfTimeEffect() =
        outOfTimeUseCase.isOutOfTime.filter { it == true }.map { UiSideEffect.OutOfTime }

    private fun revokedDeviceEffect() =
        deviceRepository.deviceState.filterIsInstance<DeviceState.Revoked>().map {
            UiSideEffect.RevokedDevice
        }

    sealed interface UiSideEffect {
        data class OpenAccountManagementPageInBrowser(val token: WebsiteAuthToken) : UiSideEffect

        data object OutOfTime : UiSideEffect

        data object RevokedDevice : UiSideEffect

        data object NoVpnPermission : UiSideEffect

        sealed interface ConnectError : UiSideEffect {
            data object Generic : ConnectError

            data object NoVpnPermission : ConnectError

            data class AlwaysOnVpn(val appName: String) : ConnectError
        }
    }

    companion object {
        const val UI_STATE_DEBOUNCE_DURATION_MILLIS: Long = 200
    }
}
