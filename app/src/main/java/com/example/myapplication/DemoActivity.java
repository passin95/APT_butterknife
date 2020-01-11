package com.example.myapplication;

import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import me.passin.butterknife.annotations.BindView;
import me.passin.butterknife.api.ButterKnife;
import me.passin.butterknife.api.Unbinder;

public class DemoActivity extends AppCompatActivity {

    private Unbinder unbinder;

    @BindView(R.id.fl_root)
    FrameLayout mFlRoot;
    @BindView(R.id.tv)
    TextView mTv;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);
        unbinder = ButterKnife.bind(this);
        mFlRoot.setBackgroundColor(getResources().getColor(R.color.colorAccent));
        mTv.setText("Hello World");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbinder.unbind();
    }
}
