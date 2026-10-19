/******************************************************************************
 * Copyright 2017 The Baidu Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/
package com.baidu.carlifevehicle.fragment;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.baidu.carlife.sdk.receiver.CarLife;
import com.baidu.carlife.sdk.util.Logger;
import com.baidu.carlife.sdk.util.TimerUtils;
import com.baidu.carlifevehicle.R;
import com.baidu.carlifevehicle.message.MsgBaseHandler;
import com.baidu.carlifevehicle.message.MsgHandlerCenter;
import com.baidu.carlifevehicle.util.CommonParams;
import com.baidu.carlifevehicle.util.ConnectTypePrefs;
import com.baidu.carlifevehicle.util.ServiceSwitchPrefs;
import com.baidu.carlifevehicle.usb.UsbAttachPolicy;
import com.baidu.carlifevehicle.view.LoadingProgressBar;

import java.util.Timer;
import java.util.TimerTask;

public class MainFragment extends BaseFragment implements OnClickListener {
    private static final String TAG = "MainFragment";

    /** iPhone 的 VID：SDK 不会为它申请 USB 权限（走的是其它通道），不算"在等授权" */
    private static final int VID_IPHONE = 0x05AC;
    private ImageView mImgView = null;
    private LoadingProgressBar mConnectProgress = null;
    private TextView mConnectInfo = null;

    private Button mRetryBtn = null;
    private TextView mHelpBtn = null;
    private TextView mSettingBtn = null;
    private ImageButton mExitAppBtn = null;
    /** 顶部栏左侧的「服务」总开关，关闭后禁止连接手机（见 ServiceSwitchPrefs） */
    private Switch mServiceSwitch = null;
    private Timer mTimer = null;
    private TimerTask mTimerTask = null;
    public static int connectTimeoutMs = 20 * 1000;
    public static final int CONNECT_TIMEOUT_MS_WIFI = 20 * 1000;
    public static final int CONNECT_TIMEOUT_MS_USB = 30 * 1000;
    public static final int CONNECT_TIMEOUT_MS_INSTALL = 60 * 1000;
    private MsgBaseHandler mHandler = null;

    public static final String MAIN_TYPE = "main_type";
    public static final int MAIN_TYPEE_NONE = 0x0000;
    public static final int MAIN_TYPE_FROM_AUTO_CONNECT = 0x0001;
    public static final int MAIN_TYPE_FROM_UNAUTO_CONNECT = 0x0002;
    public static final int MAIN_TYPE_FROM_EXCEPTION_FRAGMENT = 0x0003;
    public static final int MAIN_TYPE_FROM_SETTING_FRAGMENT = 0x0004;
    private int connectStatusHeight = 0;
    private RelativeLayout mRellayoutStatus;
    RelativeLayout.LayoutParams wParams = null;
    RelativeLayout.LayoutParams cParams = null;
    private static MainFragment mMainFragment;

    private boolean isAoaNotSupportADBNotOpen = false;
    /** 主页是否还没做过首次初始化（首次真正显示时才开始连接） */
    private boolean isFirstShowMainFragment = true;

    public static MainFragment getInstance() {
        if (mMainFragment == null) {
            mMainFragment = new MainFragment();
        }
        return mMainFragment;

    }

    public MainFragment() {
        mHandler = new MsgMainFragmentHandler();
        MsgHandlerCenter.registerMessageHandler(mHandler);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        Logger.d(TAG, "onHiddenChanged, hidden: " + hidden);
        super.onHiddenChanged(hidden);
        if (isFirstShowMainFragment && !hidden) {
            // 主页首次显示时才开始去执行连接。
            // 原本这里还会延时 30s 自动跳转帮助页，按需求已去掉：帮助页只能点顶部「帮助」按钮进入。
            beginConnect();
            isFirstShowMainFragment = false;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        Logger.d(TAG, "onCreateView");
        mContentView = (ViewGroup) LayoutInflater.from(mActivity).inflate(R.layout.frag_main, null);
        mImgView = (ImageView) mContentView.findViewById(R.id.main_img_view);
        mConnectProgress = (LoadingProgressBar) mContentView.findViewById(R.id.main_load_round_progress);
        mConnectProgress.setVisibility(View.GONE);

        mConnectInfo = (TextView) mContentView.findViewById(R.id.main_info_text_view);
        mRellayoutStatus = (RelativeLayout) mContentView.findViewById(R.id.main_rellayout_status);
        mRetryBtn = (Button) mContentView.findViewById(R.id.main_retry_btn);

        mHelpBtn = (TextView) mContentView.findViewById(R.id.main_btn_help);
        mHelpBtn.setOnClickListener(this);
        mSettingBtn = (TextView) mContentView.findViewById(R.id.main_btn_setting);
        mSettingBtn.setOnClickListener(this);
        mExitAppBtn = (ImageButton) mContentView.findViewById(R.id.exit_img_btn);
        mExitAppBtn.setOnClickListener(this);
        mServiceSwitch = (Switch) mContentView.findViewById(R.id.main_service_switch);
        // frag_main.xml 有 layout/ 与 layout-hdpi/ 两个版本，都要有 main_service_switch。
        // 万一某个变体漏加了，这里只丢开关功能，不能让主页崩掉（也不能打断后面的初始化）。
        if (mServiceSwitch != null) {
            mServiceSwitch.setChecked(ServiceSwitchPrefs.INSTANCE.isEnabled(mActivity));
            mServiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    ServiceSwitchPrefs.INSTANCE.setEnabled(mActivity, isChecked);
                    if (isChecked) {
                        // 恢复连接：transports 为空时 connect() 会自动 configConnectType() 重建
                        CarLife.receiver().connect();
                    } else {
                        // 停止连接：终止并清空所有 transport，之后不会自动重连
                        CarLife.receiver().stopConnect();
                    }
                    Logger.d(TAG, "service switch -> " + isChecked);
                }
            });
        } else {
            Logger.e(TAG, "main_service_switch not found in this layout variant!");
        }
        if (CommonParams.vehicleChannel.equals(CommonParams.VEHICLE_CHANNEL_EL_AFTER_MARKET)) {
            mRetryBtn.setVisibility(View.GONE);
            mRetryBtn = null;
            changeUILayout();
        } else {
            mRetryBtn.setOnClickListener(this);
        }

        // 超时时间取设置项（默认 10 秒，弹窗里可调），见 ConnectTypePrefs
        connectTimeoutMs = ConnectTypePrefs.INSTANCE.connectTimeoutMs(mActivity);
        Logger.d(TAG, "set timeout: " + connectTimeoutMs);
        return mContentView;
    }

    private void changeUILayout() {
        mHelpBtn.setVisibility(View.GONE);
        mSettingBtn.setVisibility(View.GONE);
        mExitAppBtn.setVisibility(View.GONE);
        if (mServiceSwitch != null) {
            mServiceSwitch.setVisibility(View.GONE);
        }
        RelativeLayout.LayoutParams params = null;

        params = (RelativeLayout.LayoutParams) mImgView.getLayoutParams();
        params.setMargins(0, 0, 0, 0);
        params.height = 200;
        mImgView.setLayoutParams(params);

        params = (RelativeLayout.LayoutParams) mConnectInfo.getLayoutParams();
        params.setMargins(0, 0, 0, 0);
        mConnectInfo.setLayoutParams(params);
        mConnectInfo.setTextSize(22.0f);

        mContentView.invalidate();
    }

    private void retryBtnClick() {
        // todo adb 连接是否需要适配
        if (mRetryBtn != null) {
            mRetryBtn.setVisibility(View.INVISIBLE);
        }
        mConnectInfo.setText(R.string.usb_not_connected);
        mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_usbwifi));
        beginConnect();

    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initParams();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    private void beginConnect() {
        // 「服务」开关关闭时禁止连接手机（用户改设置时临时停掉），见 ServiceSwitchPrefs
        if (!ServiceSwitchPrefs.INSTANCE.isEnabled(mActivity)) {
            Logger.d(TAG, "service switch is off, skip connect");
            return;
        }
        // 每次连接都重新读一次设置值，这样改完超时不用重进主页
        connectTimeoutMs = ConnectTypePrefs.INSTANCE.connectTimeoutMs(mActivity);
        CarLife.receiver().connect();
        TimerUtils.INSTANCE.schedule(mConnectionTask, connectTimeoutMs, connectTimeoutMs);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Logger.d(TAG, "onDestroyView");
    }

    @Override
    public void onDetach() {
        super.onDetach();
        Logger.d(TAG, "onDetach");
        MsgHandlerCenter.unRegisterMessageHandler(mHandler);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.main_retry_btn:
                retryBtnClick();
                Logger.e(TAG, "Retry connection when click retry button");
                break;
            case R.id.main_btn_help:
                mFragmentManager.showFragment(HelpMainFragment.getInstance());
                break;
            case R.id.main_btn_setting:
                mFragmentManager.showFragment(SettingsListFragment.getInstance());
                break;
            case R.id.exit_img_btn:
                if (mActivity != null) {
                    mActivity.openExitAppDialog();
                }
                break;

            default:
                break;
        }
    }

    private class MsgMainFragmentHandler extends MsgBaseHandler {

        @Override
        public void handleMessage(Message msg) {
            Logger.d(TAG, "MsgMainFragmentHandler handleMessage get msg: " + CommonParams.getMsgName(msg.what));
            try {
                switch (msg.what) {
                    case CommonParams.MSG_CONNECT_STATUS_CONNECTED:
                        TimerUtils.INSTANCE.stop(mConnectionTask);
                        TimerUtils.INSTANCE.schedule(mProtocolInteractionTask, connectTimeoutMs, connectTimeoutMs);
                        break;
                    case CommonParams.MSG_CONNECT_STATUS_ESTABLISHED:
                        TimerUtils.INSTANCE.stop(mProtocolInteractionTask);
                        break;
                    case CommonParams.MSG_CONNECT_FAIL_AUTHEN_FAILED:
                        mConnectProgress.setVisibility(View.GONE);
                        mConnectInfo.setText(R.string.usb_connect_fail_authen_failed);
                        mRellayoutStatus.setVisibility(View.VISIBLE);
                        mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_connect_error));
                        TimerUtils.INSTANCE.stop(mProtocolInteractionTask);
                        break;
                    case CommonParams.MSG_CONNECT_STATUS_DISCONNECTED:
                        mConnectProgress.setVisibility(View.GONE);
                        mRellayoutStatus.setVisibility(View.VISIBLE);
                        mConnectInfo.setText(R.string.usb_connect_fail);
                        mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_connect_error));
                        TimerUtils.INSTANCE.stop(mConnectionTask);
                        break;
                    case CommonParams.MSG_CONNECT_FAIL_NOT_SURPPORT:
                        mConnectProgress.setVisibility(View.GONE);
                        if (mRetryBtn != null) {
                            mRetryBtn.setVisibility(View.VISIBLE);
                        }
                        mConnectInfo.setText(R.string.usb_connect_fail_not_surpport);
                        mRellayoutStatus.setVisibility(View.VISIBLE);
                        mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_connect_error));
                        TimerUtils.INSTANCE.stop(mProtocolInteractionTask);
                        break;
                    case CommonParams.MSG_CONNECT_CHANGE_PROGRESS_NUMBER:
                        int rate = msg.arg1;
                        try {
                            if (mConnectProgress.getVisibility() != View.VISIBLE) {
                                mConnectProgress.setVisibility(View.VISIBLE);
                                mConnectProgress.resetLoadingView();
                                mRellayoutStatus.setVisibility(View.GONE);
                            }
                            mConnectProgress.updateRate(rate);
                        } catch (Exception ex) {
                            Logger.e(TAG, "set progress number error");
                            ex.printStackTrace();
                        }
                        break;
                    case CommonParams.MSG_CMD_VIDEO_ENCODER_START:
                        mConnectProgress.setVisibility(View.VISIBLE);
                        mConnectProgress.loadFinished();
                        break;
                    case CommonParams.MSG_CONNECT_FAIL_TIMEOUT:
                        mConnectProgress.setVisibility(View.GONE);
                        mConnectInfo.setText(R.string.usb_connect_fail_timeout);
                        mRellayoutStatus.setVisibility(View.VISIBLE);
                        mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_connect_error));
                        TimerUtils.INSTANCE.stop(mConnectionTask);
                        break;
                    case CommonParams.MSG_FRAGMENT_REFRESH:
                        if (isAoaNotSupportADBNotOpen) {
                            isAoaNotSupportADBNotOpen = false;
                            mConnectProgress.setVisibility(View.GONE);
                            mRellayoutStatus.setVisibility(View.VISIBLE);
                            mConnectInfo.setText(R.string.usb_connect_fail);
                            mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_connect_error));
                            Logger.d(TAG, "ChangeUI:: MSG_FRAGMENT_REFRESH  AoaNotSupportADBNotOpen ");
                        }
                        break;
                    case CommonParams.MSG_CONNECT_FAIL_START:
                        mConnectProgress.setVisibility(View.GONE);
                        mRellayoutStatus.setVisibility(View.VISIBLE);
                        mConnectInfo.setText(R.string.usb_connect_fail_install);
                        mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_qr));
                        TimerUtils.INSTANCE.stop(mProtocolInteractionTask);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                Logger.e(TAG, "change main fragment get exception");
            }
        }

        @Override
        public void careAbout() {
            addMsg(CommonParams.MSG_CONNECT_STATUS_CONNECTED);
            addMsg(CommonParams.MSG_CONNECT_STATUS_ESTABLISHED);
            addMsg(CommonParams.MSG_CONNECT_STATUS_DISCONNECTED);
            addMsg(CommonParams.MSG_CONNECT_FAIL_START);
            addMsg(CommonParams.MSG_CONNECT_FAIL_NOT_SURPPORT);
            addMsg(CommonParams.MSG_CONNECT_FAIL_START_BDSC);
            addMsg(CommonParams.MSG_CONNECT_FAIL);
            addMsg(CommonParams.MSG_CONNECT_AOA_NO_PERMISSION);
            addMsg(CommonParams.MSG_CONNECT_AOA_REQUEST_MD_PERMISSION);
            addMsg(CommonParams.MSG_CONNECT_AOA_NOT_SUPPORT);
            addMsg(CommonParams.MSG_CONNECT_FAIL_TIMEOUT);
            addMsg(CommonParams.MSG_CONNECT_FAIL_AUTHEN_FAILED);

            addMsg(CommonParams.MSG_CONNECT_CHANGE_PROGRESS_NUMBER);

            addMsg(CommonParams.MSG_CMD_VIDEO_ENCODER_START);
        }
    }

    @Override
    public boolean onBackPressed() {
        if (mActivity != null) {
            mActivity.openExitAppDialog();
        }
        return true;
    }

    private Runnable mConnectionTask = new Runnable() {
        @Override
        public void run() {
            // 首次连接要弹两次系统授权框（手机普通模式一次、切到 AOA 后新 VID/PID 再一次），
            // 用户点完通常要十几秒，远超默认超时（10s）。还在等 USB 授权时不报超时，
            // 否则界面会在授权途中闪"连接超时"，而底层其实还在正常推进。
            if (isWaitingUsbPermission()) {
                Logger.d(TAG, "waiting for USB permission, skip connect timeout");
                return;
            }
            Logger.e(TAG, "Carlife Connect Timeout");
            MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_FAIL_TIMEOUT);
        }
    };

    /**
     * 当前是否存在"已插上但还没授权"的手机类 USB 设备。
     * 只判断非手机类（U 盘/鼠标）之外的设备，见 {@link UsbAttachPolicy}。
     */
    private boolean isWaitingUsbPermission() {
        if (mActivity == null) {
            return false;
        }
        try {
            UsbManager usbManager = (UsbManager) mActivity.getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                return false;
            }
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (usbManager.hasPermission(device)) {
                    continue;
                }
                // iPhone（SDK 侧不会为它申请权限）与非手机类设备都不算"在等授权"
                if (device.getVendorId() == VID_IPHONE
                        || UsbAttachPolicy.INSTANCE.isNonPhoneDevice(device)) {
                    continue;
                }
                Logger.d(TAG, "usb device without permission: vid=" + device.getVendorId()
                        + " pid=" + device.getProductId());
                return true;
            }
        } catch (Exception e) {
            Logger.e(TAG, "check usb permission failed: ", e);
        }
        return false;
    }

    private Runnable mProtocolInteractionTask = new Runnable() {
        @Override
        public void run() {
            Logger.e(TAG, "Carlife Protocol interaction Timeout");
            MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_FAIL_START);
        }
    };

    private void initParams() {
        if (wParams == null) {
            connectStatusHeight = getResources().getDimensionPixelSize(R.dimen.connect_status_height);
            Logger.d(TAG, "connectStatusHeight=" + connectStatusHeight);
            wParams = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            int screenHeigh = 12; // PhoneUtil.getInstance().getScreenHeight();
            int screenWidth = 13; // PhoneUtil.getInstance().getScreenWidth();
            Logger.d(TAG, "screenHeigh=" + screenHeigh + ",screenWidth=" + screenWidth);
            wParams.topMargin = ((screenHeigh - connectStatusHeight) * 2) / 3;
            wParams.bottomMargin = (screenHeigh - connectStatusHeight) / 3;
            Logger.d(TAG, "topMargin=" + wParams.topMargin + ",bottomMargin" + wParams.bottomMargin);
        }
        if (cParams == null) {
            cParams = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            cParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        }
    }

    public void updateExceptionTips(String exceptionTips) {
        if (isAoaNotSupportADBNotOpen) {
            String hintResStr = getResources().getString(R.string.usb_connect_aoa_request_md_permisson);
            if (hintResStr.equals(exceptionTips)) {
                return;
            }
        }

        if (!TextUtils.isEmpty(exceptionTips) && mConnectInfo != null) {
            mConnectProgress.setVisibility(View.GONE);
            mRellayoutStatus.setVisibility(View.VISIBLE);
            mImgView.setImageDrawable(getResources().getDrawable(R.drawable.car_ic_connect_error));
            mConnectInfo.setText(exceptionTips);
        }
    }
}