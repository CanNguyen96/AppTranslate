package com.ptithcm.apptranslate.fragments;

import android.graphics.Color;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.ptithcm.apptranslate.R;
import com.ptithcm.apptranslate.services.FloatingService;
import com.ptithcm.apptranslate.services.ScreenCaptureService;
import com.ptithcm.apptranslate.utils.PermissionUtils;

public class HomeFragment extends Fragment {

    private TextView tvStatus;
    private Button btnRequestPermission;
    private Button btnStartService;
    private Button btnStopApp;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvStatus = view.findViewById(R.id.tvStatus);
        btnRequestPermission = view.findViewById(R.id.btnRequestPermission);
        btnStartService = view.findViewById(R.id.btnStartService);
        btnStopApp = view.findViewById(R.id.btnStopApp);

        btnRequestPermission.setOnClickListener(v -> PermissionUtils.requestOverlayPermission(requireActivity()));

        btnStartService.setOnClickListener(v -> startTranslationService());

        btnStopApp.setOnClickListener(v -> stopAppAndBackground());
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        if (PermissionUtils.hasOverlayPermission(requireContext())) {
            tvStatus.setText("Trạng thái quyền: Đã cấp");
            tvStatus.setTextColor(Color.GREEN);
            btnRequestPermission.setVisibility(View.GONE);
            btnStartService.setEnabled(true);
        } else {
            tvStatus.setText("Trạng thái quyền: Chưa cấp (Cần Overlay)");
            tvStatus.setTextColor(Color.RED);
            btnRequestPermission.setVisibility(View.VISIBLE);
            btnStartService.setEnabled(false);
        }
    }

    private void startTranslationService() {
        if (PermissionUtils.hasOverlayPermission(requireContext())) {
            Intent intent = new Intent(requireContext(), FloatingService.class);
            ContextCompat.startForegroundService(requireContext(), intent);
            requireActivity().finish();
        } else {
            Toast.makeText(requireContext(), "Vui lòng cấp quyền Overlay trước", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAppAndBackground() {
        try {
            requireContext().stopService(new Intent(requireContext(), FloatingService.class));
        } catch (Exception ignored) {
        }

        try {
            Intent stopProjection = new Intent(requireContext(), ScreenCaptureService.class);
            stopProjection.setAction(ScreenCaptureService.ACTION_STOP_PROJECTION);
            ContextCompat.startForegroundService(requireContext(), stopProjection);
        } catch (Exception ignored) {
        }

        try {
            requireContext().stopService(new Intent(requireContext(), ScreenCaptureService.class));
        } catch (Exception ignored) {
        }

        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(requireContext());
            nm.cancel(1);
            nm.cancel(2);
            nm.cancel(2002);
        } catch (Exception ignored) {
        }

        requireActivity().finishAndRemoveTask();
    }
}
