package com.popmemo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_SPEECH = 1;

    private MemoStore store;
    private SharedPreferences prefs;
    private final List<Memo> memos = new ArrayList<>();
    private MemoAdapter adapter;

    private EditText input;
    private ListView list;
    private TextView empty;
    private Switch serviceSwitch;
    private boolean suppressSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new MemoStore(this);
        prefs = getSharedPreferences("popmemo", MODE_PRIVATE);
        memos.addAll(store.load());

        input = findViewById(R.id.input);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        Button voice = findViewById(R.id.voice);
        serviceSwitch = findViewById(R.id.service_switch);

        // Android 15 强制全面屏，不自己留出状态栏的位置就会被时间压住。
        View rootView = findViewById(R.id.root);
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12), dp(12) + top, dp(12), dp(12) + bottom);
            return insets;
        });

        serviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) {
                return;
            }
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    askOverlayPermission();
                    setSwitchSilently(false);
                    return;
                }
                requestNotificationPermissionIfNeeded();
                prefs.edit().putBoolean(UnlockService.PREF_ENABLED, true).apply();
                startForegroundService(new Intent(this, UnlockService.class));
            } else {
                prefs.edit().putBoolean(UnlockService.PREF_ENABLED, false).apply();
                stopService(new Intent(this, UnlockService.class));
            }
        });

        adapter = new MemoAdapter();
        list.setAdapter(adapter);
        refreshEmpty();

        // 回车即存
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (done || enter) {
                addCurrentInput();
                return true;
            }
            return false;
        });

        voice.setOnClickListener(v -> startVoiceInput());

        // 点一下 = 标记 / 取消「重要」，解锁时只弹重要的
        list.setOnItemClickListener((parent, view, position, id) -> {
            Memo memo = memos.get(position);
            memo.important = !memo.important;
            store.save(memos);
            adapter.notifyDataSetChanged();
            Toast.makeText(this,
                    memo.important ? "已标为重要，解锁时会弹" : "不再弹了",
                    Toast.LENGTH_SHORT).show();
        });

        // 长按删除
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Memo memo = memos.get(position);
            new AlertDialog.Builder(this)
                    .setMessage("删掉「" + memo.text + "」？")
                    .setNegativeButton("留着", null)
                    .setPositiveButton("删掉", (d, w) -> {
                        memos.remove(position);
                        store.save(memos);
                        adapter.notifyDataSetChanged();
                        refreshEmpty();
                    })
                    .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = prefs.getBoolean(UnlockService.PREF_ENABLED, false);
        setSwitchSilently(enabled);
        if (enabled && Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, UnlockService.class));
        }
    }

    private void setSwitchSilently(boolean checked) {
        suppressSwitch = true;
        serviceSwitch.setChecked(checked);
        suppressSwitch = false;
    }

    private void askOverlayPermission() {
        new AlertDialog.Builder(this)
                .setTitle("还差一个权限")
                .setMessage("要盖在屏幕最上面弹卡片，得先允许「显示在其他应用上层」。")
                .setNegativeButton("算了", null)
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                    }
                })
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
    }

    private void addCurrentInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        memos.add(0, new Memo(System.currentTimeMillis(), text, false, System.currentTimeMillis(), 0L));
        store.save(memos);
        input.setText("");
        adapter.notifyDataSetChanged();
        refreshEmpty();
        list.setSelection(0);
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "说，我在听");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "这台手机没有语音识别", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                input.setText(results.get(0));
                input.setSelection(input.getText().length());
            }
        }
    }

    private void refreshEmpty() {
        empty.setVisibility(memos.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class MemoAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return memos.size();
        }

        @Override
        public Memo getItem(int position) {
            return memos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return memos.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(MainActivity.this);
                tv.setPadding(dp(8), dp(14), dp(8), dp(14));
                tv.setTextSize(17f);
                tv.setTextColor(0xFF111111);
            }
            Memo memo = getItem(position);
            tv.setText(memo.important ? "★ " + memo.text : memo.text);
            return tv;
        }
    }
}
