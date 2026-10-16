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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.baidu.carlife.sdk.util.Logger;
import com.baidu.carlifevehicle.R;
import com.baidu.carlifevehicle.util.AudioChannelDialog;
import com.baidu.carlifevehicle.util.AudioChannelPrefs;
import com.baidu.carlifevehicle.util.AudioTransmissionDialog;
import com.baidu.carlifevehicle.util.ConnectTypeDialog;
import com.baidu.carlifevehicle.util.ConnectTypePrefs;
import com.baidu.carlifevehicle.util.DisplayModeDialog;
import com.baidu.carlifevehicle.util.DisplayModePrefs;
import com.baidu.carlifevehicle.util.ThemeSyncDialog;
import com.baidu.carlifevehicle.util.ThemeSyncPrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：标题栏（含返回箭头）+ 设置项列表。
 *
 * 当前条目：
 *  - 连接方式：USB / WIFI / 直连，点击弹窗选择（见 {@link ConnectTypeDialog}）
 *    （「USB拉起APP」开关放在该弹窗内、选中 USB 时才显示，不再单独占一行）
 *  - 显示方式：系统默认 / 沉浸式全屏（见 {@link DisplayModeDialog}）
 *  - 音频传输通道：互联通道 / 蓝牙通道（见 {@link AudioTransmissionDialog}）
 *  - 媒体 / 导航音频通道：**仅当音频走「互联通道」时出现**（见 {@link AudioChannelDialog}）
 *  - 主题同步：把车机深浅色同步给手机端（见 {@link ThemeSyncDialog}）
 */
public class SettingsListFragment extends BaseFragment {

    private static final String TAG = "SettingsListFragment";

    private static SettingsListFragment mSettingsListFragment;

    /** 条目 id */
    private static final int ITEM_CONNECT_TYPE = 0;
    private static final int ITEM_DISPLAY_MODE = 1;
    private static final int ITEM_AUDIO_TRANSMISSION = 3;
    private static final int ITEM_AUDIO_MEDIA_CHANNEL = 4;
    private static final int ITEM_AUDIO_NAVI_CHANNEL = 5;
    private static final int ITEM_THEME_SYNC = 6;

    /** 当前可见的条目（顺序即列表顺序，由 refreshItems 决定） */
    private final List<Integer> mVisibleItems = new ArrayList<>();

    private ImageButton mBackBtn;
    private TextView mTitle;
    private ListView mSettingList;
    private SettingAdapter mAdapter;

    public static SettingsListFragment getInstance() {
        if (mSettingsListFragment == null) {
            mSettingsListFragment = new SettingsListFragment();
        }
        return mSettingsListFragment;
    }

    public SettingsListFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        Logger.d(TAG, "onCreateView");
        mContentView = (ViewGroup) LayoutInflater.from(mActivity)
                .inflate(R.layout.frag_settings_list, null);

        mBackBtn = (ImageButton) mContentView.findViewById(R.id.ib_left);
        mBackBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        mTitle = (TextView) mContentView.findViewById(R.id.tv_title);
        mTitle.setText(getString(R.string.module_setting));

        mAdapter = new SettingAdapter();
        mSettingList = (ListView) mContentView.findViewById(R.id.settings_list);
        mSettingList.setAdapter(mAdapter);
        mSettingList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onSettingItemClick(position);
            }
        });
        refreshItems();

        return mContentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 回到设置页时同步一次（连接方式可能在别处被改过）
        refreshItems();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Logger.d(TAG, "onDestroyView");
    }

    /**
     * 按当前设置刷新可见条目：
     *  - 「媒体 / 导航音频通道」只在音频走「互联通道」时出现（蓝牙通道下车机不接管音频）。
     */
    private void refreshItems() {
        mVisibleItems.clear();
        mVisibleItems.add(ITEM_CONNECT_TYPE);
        mVisibleItems.add(ITEM_DISPLAY_MODE);
        mVisibleItems.add(ITEM_AUDIO_TRANSMISSION);
        if (AudioChannelPrefs.INSTANCE.channelSettingsEnabled(mActivity)) {
            mVisibleItems.add(ITEM_AUDIO_MEDIA_CHANNEL);
            mVisibleItems.add(ITEM_AUDIO_NAVI_CHANNEL);
        }
        mVisibleItems.add(ITEM_THEME_SYNC);
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void onSettingItemClick(int position) {
        if (position < 0 || position >= mVisibleItems.size()) {
            return;
        }
        switch (mVisibleItems.get(position)) {
            case ITEM_CONNECT_TYPE:
                if (mActivity instanceof AppCompatActivity) {
                    ConnectTypeDialog.INSTANCE.show((AppCompatActivity) mActivity, new Runnable() {
                        @Override
                        public void run() {
                            refreshItems();
                        }
                    });
                } else {
                    Logger.e(TAG, "activity is not AppCompatActivity, can not show dialog");
                }
                break;
            case ITEM_DISPLAY_MODE:
            case ITEM_AUDIO_TRANSMISSION:
            case ITEM_AUDIO_MEDIA_CHANNEL:
            case ITEM_AUDIO_NAVI_CHANNEL:
            case ITEM_THEME_SYNC:
                showSettingsDialog(mVisibleItems.get(position));
                break;
            default:
                break;
        }
    }

    /** 需要弹窗的条目：显示方式 / 音频传输通道 / 媒体·导航音频通道 / 主题同步 */
    private void showSettingsDialog(int item) {
        if (!(mActivity instanceof AppCompatActivity)) {
            Logger.e(TAG, "activity is not AppCompatActivity, can not show dialog");
            return;
        }
        AppCompatActivity activity = (AppCompatActivity) mActivity;
        Runnable onChanged = new Runnable() {
            @Override
            public void run() {
                refreshItems();
            }
        };
        switch (item) {
            case ITEM_DISPLAY_MODE:
                DisplayModeDialog.INSTANCE.show(activity, onChanged);
                break;
            case ITEM_AUDIO_TRANSMISSION:
                AudioTransmissionDialog.INSTANCE.show(activity, onChanged);
                break;
            case ITEM_AUDIO_MEDIA_CHANNEL:
                AudioChannelDialog.INSTANCE.show(activity, AudioChannelDialog.KIND_MEDIA, onChanged);
                break;
            case ITEM_AUDIO_NAVI_CHANNEL:
                AudioChannelDialog.INSTANCE.show(activity, AudioChannelDialog.KIND_NAVI, onChanged);
                break;
            case ITEM_THEME_SYNC:
                ThemeSyncDialog.INSTANCE.show(activity, onChanged);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onBackPressed() {
        if (mFragmentManager != null) {
            mFragmentManager.showFragment(MainFragment.getInstance());
        }
        return true;
    }

    private class SettingAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mVisibleItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mVisibleItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mVisibleItems.get(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(mActivity)
                        .inflate(R.layout.item_settings, parent, false);
                holder = new ViewHolder();
                holder.title = (TextView) convertView.findViewById(R.id.tv_settings_title);
                holder.value = (TextView) convertView.findViewById(R.id.tv_settings_value);
                holder.more = (ImageView) convertView.findViewById(R.id.iv_settings_more);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            switch (mVisibleItems.get(position)) {
                case ITEM_CONNECT_TYPE:
                    holder.title.setText(R.string.settings_connect_type);
                    holder.value.setText(ConnectTypePrefs.INSTANCE.typeName(mActivity));
                    holder.more.setVisibility(View.VISIBLE);
                    break;
                case ITEM_DISPLAY_MODE:
                    holder.title.setText(R.string.display_mode_title);
                    holder.value.setText(DisplayModePrefs.INSTANCE.modeNameRes(
                            DisplayModePrefs.INSTANCE.currentMode(mActivity)));
                    holder.more.setVisibility(View.VISIBLE);
                    break;
                case ITEM_AUDIO_TRANSMISSION:
                    holder.title.setText(R.string.audio_transmission_title);
                    holder.value.setText(AudioChannelPrefs.INSTANCE.transmissionNameRes(
                            AudioChannelPrefs.INSTANCE.transmissionMode(mActivity)));
                    holder.more.setVisibility(View.VISIBLE);
                    break;
                case ITEM_AUDIO_MEDIA_CHANNEL:
                    holder.title.setText(R.string.audio_media_channel_title);
                    holder.value.setText(AudioChannelPrefs.INSTANCE.channelName(mActivity,
                            AudioChannelPrefs.INSTANCE.mediaChannel(mActivity),
                            AudioChannelPrefs.INSTANCE.mediaLegacyApi(mActivity)));
                    holder.more.setVisibility(View.VISIBLE);
                    break;
                case ITEM_AUDIO_NAVI_CHANNEL:
                    holder.title.setText(R.string.audio_navi_channel_title);
                    holder.value.setText(AudioChannelPrefs.INSTANCE.channelName(mActivity,
                            AudioChannelPrefs.INSTANCE.naviChannel(mActivity),
                            AudioChannelPrefs.INSTANCE.naviLegacyApi(mActivity)));
                    holder.more.setVisibility(View.VISIBLE);
                    break;
                case ITEM_THEME_SYNC:
                    holder.title.setText(R.string.theme_sync_title);
                    holder.value.setText(ThemeSyncPrefs.INSTANCE.isEnabled(mActivity)
                            ? R.string.connect_type_remember_on
                            : R.string.connect_type_remember_off);
                    holder.more.setVisibility(View.VISIBLE);
                    break;
                default:
                    break;
            }
            return convertView;
        }
    }

    private static class ViewHolder {
        TextView title;
        TextView value;
        ImageView more;
    }
}
