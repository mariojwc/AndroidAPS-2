package info.nightscout.androidaps.plugins.pump.omnipod.service;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;

import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;

import info.nightscout.androidaps.interfaces.DatabaseHelperInterface;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.pump.common.defs.PumpDeviceState;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkCommunicationManager;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkConst;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.defs.RileyLinkTargetDevice;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.RileyLinkService;
import info.nightscout.androidaps.plugins.pump.omnipod.OmnipodPumpPlugin;
import info.nightscout.androidaps.plugins.pump.omnipod.R;
import info.nightscout.androidaps.plugins.pump.omnipod.comm.OmnipodCommunicationManager;
import info.nightscout.androidaps.plugins.pump.omnipod.defs.state.PodStateManager;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.comm.AapsOmnipodManager;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.ui.OmnipodUIComm;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.ui.OmnipodUIPostprocessor;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodPumpValuesChanged;
import info.nightscout.androidaps.plugins.pump.omnipod.util.OmnipodUtil;


/**
 * Created by andy on 4.8.2019
 * RileyLinkOmnipodService is intended to stay running when the gui-app is closed.
 */
public class RileyLinkOmnipodService extends RileyLinkService {

    private static final String REGEX_MAC = "([\\da-fA-F]{1,2}(?:\\:|$)){6}";

    @Inject OmnipodPumpPlugin omnipodPumpPlugin;
    @Inject OmnipodUtil omnipodUtil;
    @Inject OmnipodUIPostprocessor omnipodUIPostprocessor;
    @Inject PodStateManager podStateManager;
    @Inject DatabaseHelperInterface databaseHelper;
    @Inject AapsOmnipodManager aapsOmnipodManager;
    @Inject OmnipodCommunicationManager omnipodCommunicationManager;

    private IBinder mBinder = new LocalBinder();
    private boolean rileyLinkAddressChanged = false;
    private boolean inPreInit = true;
    private String rileyLinkAddress;
    private String errorDescription;

    OmnipodUIComm omnipodUIComm;

    public RileyLinkOmnipodService() {
        super();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        aapsLogger.warn(LTag.PUMPCOMM, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public RileyLinkEncodingType getEncoding() {
        return RileyLinkEncodingType.Manchester;
    }

    /**
     * If you have customized RileyLinkServiceData you need to override this
     */
    @Override
    public void initRileyLinkServiceData() {

        rileyLinkServiceData.targetDevice = RileyLinkTargetDevice.Omnipod;
        rileyLinkServiceData.rileyLinkTargetFrequency = RileyLinkTargetFrequency.Omnipod;

        // get most recently used RileyLink address
        rileyLinkServiceData.rileylinkAddress = sp.getString(RileyLinkConst.Prefs.RileyLinkAddress, "");

        rfspy.startReader();

        initializeErosOmnipodManager();

        aapsLogger.debug(LTag.PUMPCOMM, "RileyLinkOmnipodService newly constructed");
        //omnipodPumpStatus = (OmnipodPumpStatus) omnipodPumpPlugin.getPumpStatusData();
    }

    private void initializeErosOmnipodManager() {
        if (omnipodUIComm == null) {
            omnipodUIComm = new OmnipodUIComm(injector, aapsLogger, omnipodUIPostprocessor, aapsOmnipodManager, rileyLinkUtil);
        }
        rxBus.send(new EventOmnipodPumpValuesChanged());
    }

    public OmnipodUIComm getDeviceCommandExecutor() {
        return this.omnipodUIComm;
    }

    @Override
    public RileyLinkCommunicationManager getDeviceCommunicationManager() {
        return omnipodCommunicationManager;
    }

    @Override
    public void setPumpDeviceState(PumpDeviceState pumpDeviceState) {
        // Intentionally left blank
        // We don't use PumpDeviceState in the Omnipod driver
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public class LocalBinder extends Binder {
        public RileyLinkOmnipodService getServiceInstance() {
            return RileyLinkOmnipodService.this;
        }
    }

    /* private functions */

    // PumpInterface - REMOVE

    public boolean isInitialized() {
        return rileyLinkServiceData.rileyLinkServiceState.isReady();
    }

    @Override
    public boolean verifyConfiguration() {
        try {
            errorDescription = null;

            String rileyLinkAddress = sp.getString(RileyLinkConst.Prefs.RileyLinkAddress, "");

            if (StringUtils.isEmpty(rileyLinkAddress)) {
                aapsLogger.debug(LTag.PUMPCOMM, "RileyLink address invalid: no address");
                errorDescription = resourceHelper.gs(R.string.omnipod_error_rileylink_address_invalid);
                return false;
            } else {
                if (!rileyLinkAddress.matches(REGEX_MAC)) {
                    errorDescription = resourceHelper.gs(R.string.omnipod_error_rileylink_address_invalid);
                    aapsLogger.debug(LTag.PUMPCOMM, "RileyLink address invalid: {}", rileyLinkAddress);
                } else {
                    if (!rileyLinkAddress.equals(this.rileyLinkAddress)) {
                        this.rileyLinkAddress = rileyLinkAddress;
                        rileyLinkAddressChanged = true;
                    }
                }
            }

            rileyLinkServiceData.rileyLinkTargetFrequency = RileyLinkTargetFrequency.Omnipod;

            reconfigureService();

            return true;

        } catch (Exception ex) {
            errorDescription = ex.getMessage();
            aapsLogger.error(LTag.PUMPCOMM, "Error on Verification: " + ex.getMessage(), ex);
            return false;
        }
    }

    private boolean reconfigureService() {
        if (!inPreInit) {
            if (rileyLinkAddressChanged) {
                rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkNewAddressSet, this);
                rileyLinkAddressChanged = false;
            }
        }

        return (!rileyLinkAddressChanged);
    }

    public boolean setNotInPreInit() {
        this.inPreInit = false;

        return reconfigureService();
    }
}
