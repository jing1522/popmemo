package com.popmemo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText("PopMemo 骨架\n先证明能编译。");
        tv.setTextSize(20f);
        setContentView(tv);
    }
}
