package com.ptithcm.apptranslate.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.ptithcm.apptranslate.R;

public class SettingsFragment extends Fragment {

    private static final String PREFS_NAME = "apptranslate_settings";
    public static final String KEY_AUTO_DISMISS = "auto_dismiss_overlay";

    private SwitchCompat switchAutoDismiss;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switchAutoDismiss = view.findViewById(R.id.switchAutoDismiss);

        // Đọc setting đã lưu
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoDismiss = prefs.getBoolean(KEY_AUTO_DISMISS, false);
        switchAutoDismiss.setChecked(autoDismiss);

        // Lưu khi user thay đổi
        switchAutoDismiss.setOnCheckedChangeListener(
                (buttonView, isChecked) -> prefs.edit().putBoolean(KEY_AUTO_DISMISS, isChecked).apply());
    }
}
