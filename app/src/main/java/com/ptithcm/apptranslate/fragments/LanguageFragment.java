package com.ptithcm.apptranslate.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ptithcm.apptranslate.R;
import com.ptithcm.apptranslate.translate.LanguageManager;
import com.ptithcm.apptranslate.translate.OnDeviceTranslator;
import com.ptithcm.apptranslate.translate.SupportedLang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LanguageFragment extends Fragment {

    private Spinner spSourceLang;
    private Spinner spTargetLang;

    private List<SupportedLang> sourceOptions;
    private List<SupportedLang> targetOptions;

    private OnDeviceTranslator modelPreloader;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_language, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spSourceLang = view.findViewById(R.id.spSourceLang);
        spTargetLang = view.findViewById(R.id.spTargetLang);

        setupLanguagePickers();
    }

    private void setupLanguagePickers() {
        LanguageManager.init(requireContext());
        modelPreloader = new OnDeviceTranslator(requireContext().getApplicationContext());

        sourceOptions = Arrays.asList(
                SupportedLang.AUTO,
                SupportedLang.EN,
                SupportedLang.ZH,
                SupportedLang.JA,
                SupportedLang.KO,
                SupportedLang.VI);

        // Target không được là AUTO
        targetOptions = Arrays.asList(
                SupportedLang.VI,
                SupportedLang.EN,
                SupportedLang.ZH,
                SupportedLang.JA,
                SupportedLang.KO);

        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                toDisplayNames(sourceOptions));
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSourceLang.setAdapter(sourceAdapter);

        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                toDisplayNames(targetOptions));
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTargetLang.setAdapter(targetAdapter);

        // Khôi phục lựa chọn đã lưu
        SupportedLang currentSource = LanguageManager.getSourceLang();
        SupportedLang currentTarget = LanguageManager.getTargetLang();

        modelPreloader.preloadModels(currentSource, currentTarget);

        spSourceLang.setSelection(Math.max(0, sourceOptions.indexOf(currentSource)));
        int targetIndex = targetOptions.indexOf(currentTarget);
        spTargetLang.setSelection(targetIndex >= 0 ? targetIndex : 0);

        spSourceLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= sourceOptions.size())
                    return;
                SupportedLang selected = sourceOptions.get(position);
                LanguageManager.setSourceLang(requireContext(), selected);
                modelPreloader.preloadModels(LanguageManager.getSourceLang(), LanguageManager.getTargetLang());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spTargetLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= targetOptions.size())
                    return;
                SupportedLang selected = targetOptions.get(position);
                LanguageManager.setTargetLang(requireContext(), selected);
                modelPreloader.preloadModels(LanguageManager.getSourceLang(), LanguageManager.getTargetLang());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private static List<String> toDisplayNames(List<SupportedLang> langs) {
        List<String> out = new ArrayList<>(langs.size());
        for (SupportedLang l : langs) {
            out.add(l.displayName);
        }
        return out;
    }
}
