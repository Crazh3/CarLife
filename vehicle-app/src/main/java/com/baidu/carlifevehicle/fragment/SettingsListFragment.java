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

import com.baidu.carlife.sdk.CarLifeContext;
import com.baidu.carlife.sdk.util.Logger;
import com.baidu.carlifevehicle.R;
import com.baidu.carlifevehicle.util.ConnectTypeDialog;
import com.baidu.carlifevehicle.util.ConnectTypePrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：标题栏（含返回箭头）+ 设置项列表。
 *
 * 当前条目：
 *  - 连接方式：USB / WIFI / 直连，点击弹窗选择（见 {@link ConnectTypeDialog}）
 *  - 记住授权：**仅当连接方式为 USB 时出现**，控制 USB 授权弹窗能否勾选「一律允许」
 *    （见 {@link ConnectTypePrefs#applyRememberGrant}）
 */
public class SettingsListFragment extends BaseFragment {

    private static final String TAG = "SettingsListFragment";

    private static SettingsListFragment mSettingsListFragment;

    /** 条目 id */
    private static final int ITEM_CONNECT_TYPE = 0;
    private static final int ITEM_REMEMBER_GRANT = 1;

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
     * 按当前连接方式刷新可见条目：「记住授权」只在 USB 下出现。
     */
    private void refreshItems() {
        mVisibleItems.clear();
        mVisibleItems.add(ITEM_CONNECT_TYPE);
        if (ConnectTypePrefs.INSTANCE.currentType() == CarLifeContext.CONNECTION_TYPE_AOA) {
            mVisibleItems.add(ITEM_REMEMBER_GRANT);
        }
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
            case ITEM_REMEMBER_GRANT:
                boolean enabled = !ConnectTypePrefs.INSTANCE.isRememberGrant(mActivity);
                ConnectTypePrefs.INSTANCE.setRememberGrant(mActivity, enabled);
                Logger.d(TAG, "remember grant changed to " + enabled);
                refreshItems();
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
                case ITEM_REMEMBER_GRANT:
                    holder.title.setText(R.string.connect_type_remember_title);
                    holder.value.setText(ConnectTypePrefs.INSTANCE.isRememberGrant(mActivity)
                            ? R.string.connect_type_remember_on
                            : R.string.connect_type_remember_off);
                    holder.more.setVisibility(View.GONE);
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
