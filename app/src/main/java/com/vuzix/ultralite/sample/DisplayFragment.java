package com.vuzix.ultralite.sample; // Adjust package name as needed

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.vuzix.ultralite.UltraliteSDK;

public class DisplayFragment extends Fragment {

    // SharedPreferences constants (can be moved to a common place if needed)
    public static final String PREFS_NAME = "MyPrefsFile";
    public static final String LAST_INPUT_KEY = "lastInput";

    private EditText textInput;
    private MainActivity.DemoActivityViewModel model; // Assuming your ViewModel is in MainActivity
    private UltraliteSDK ultralite;

    private TextView batteryLevelTextView;
    private Handler batteryUpdateHandler;
    private Runnable batteryUpdateRunnable;
    private static final long BATTERY_UPDATE_INTERVAL_MS = 30000; // 30 seconds
    private int lastReportedBatteryLevelForGlasses = -100; // Initialize to a value that forces first update
    private static final int BATTERY_REPORTING_INCREMENT = 5;


    public DisplayFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize ViewModel here if it's Fragment-specific
        // Or get it from the Activity if it's shared
        model = new ViewModelProvider(requireActivity()).get(MainActivity.DemoActivityViewModel.class);
        ultralite = UltraliteSDK.get(requireContext().getApplicationContext());
        batteryUpdateHandler = new Handler(Looper.getMainLooper());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_display, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        batteryLevelTextView = view.findViewById(R.id.batteryLevelTextView);

        // Initialize views from the fragment's layout
        ImageView installedImageView = view.findViewById(R.id.installed);
        ImageView linkedImageView = view.findViewById(R.id.linked);
        TextView nameTextView = view.findViewById(R.id.name);
        ImageView connectedImageView = view.findViewById(R.id.connected);
        ImageView controlledImageView = view.findViewById(R.id.controlled);

        textInput = view.findViewById(R.id.textBox);
        Button displayButton = view.findViewById(R.id.displayTextButton);
        Button clearButton = view.findViewById(R.id.clearTextButton);

        // Load saved text
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedInput = prefs.getString(LAST_INPUT_KEY, "");
        textInput.setText(savedInput);

        // Setup Observers (move from Activity to here)
        ultralite.getAvailable().observe(getViewLifecycleOwner(), available -> {
            installedImageView.setImageResource(available ? R.drawable.ic_check_24 : R.drawable.ic_close_24);
            updateBatteryDisplayVisibility(available && ultralite.getConnected().getValue() != null && ultralite.getConnected().getValue());
        });

        ultralite.getLinked().observe(getViewLifecycleOwner(), linked -> {
            linkedImageView.setImageResource(linked ? R.drawable.ic_check_24 : R.drawable.ic_close_24);
            nameTextView.setText(ultralite.getName());
            updateBatteryDisplayVisibility(ultralite.getAvailable().getValue() != null && ultralite.getAvailable().getValue() && linked && ultralite.getConnected().getValue() != null && ultralite.getConnected().getValue());
        });

        ultralite.getConnected().observe(getViewLifecycleOwner(), connected -> {
            connectedImageView.setImageResource(connected ? R.drawable.ic_check_24 : R.drawable.ic_close_24);
            displayButton.setEnabled(connected);
            clearButton.setEnabled(connected);
            updateBatteryDisplayVisibility(ultralite.getAvailable().getValue() != null && ultralite.getAvailable().getValue() && ultralite.getLinked().getValue() != null && ultralite.getLinked().getValue() && connected);
            if (connected) {
                startBatteryMonitoringForGlasses(); // Changed method name for clarity
            } else {
                stopBatteryMonitoringForGlasses();
                model.clearGlassesBatteryPrefix(); // Tell ViewModel to clear prefix
            }
        });

        ultralite.getControlledByMe().observe(getViewLifecycleOwner(), controlled -> {
            controlledImageView.setImageResource(controlled ? R.drawable.ic_check_24 : R.drawable.ic_close_24);
            // nameTextView.setText(ultralite.getName()); // Already set in linked observer
        });


        displayButton.setOnClickListener(v -> {
            String originalMessage = textInput.getText().toString();
            SharedPreferences.Editor editor = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            editor.putString(LAST_INPUT_KEY, originalMessage);
            editor.apply();
            // ViewModel will now handle prepending battery info
            model.displayTextOnGlasses(originalMessage);
        });

        clearButton.setOnClickListener(v -> {
            if (ultralite != null) {
                ultralite.releaseControl();
            }
        });

        // Initial check in case already connected when view is created
        if (ultralite.getConnected().getValue() != null && ultralite.getConnected().getValue()) {
            startBatteryMonitoringForGlasses();
        }
    }
    private void updateBatteryDisplayVisibility(boolean show) {
        if (batteryLevelTextView != null) {
            batteryLevelTextView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void startBatteryMonitoringForGlasses() {
        if (batteryUpdateRunnable == null) {
            batteryUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (ultralite != null && ultralite.getConnected().getValue() != null && ultralite.getConnected().getValue()) {
                        try {
                            int currentBatteryLevel = ultralite.getBatteryLevel();

                            if (batteryLevelTextView != null) {
                                batteryLevelTextView.setText("Battery: " + currentBatteryLevel + "%");
                            }

                            // Check if the change is significant (>= 5%) or first time
                            if (Math.abs(currentBatteryLevel - lastReportedBatteryLevelForGlasses) >= BATTERY_REPORTING_INCREMENT ||
                                    lastReportedBatteryLevelForGlasses == -100 ) {

                                // Round to nearest 5% for display if desired, or use actual
                                // For simplicity, we'll use the actual level if it passes the increment check
                                model.updateGlassesBatteryPrefix("🔋" + currentBatteryLevel + "% ");
                                lastReportedBatteryLevelForGlasses = currentBatteryLevel; // Update last reported
                            }
                            batteryUpdateHandler.postDelayed(this, BATTERY_UPDATE_INTERVAL_MS);
                        } catch (Exception e) {
                            if (batteryLevelTextView != null) {
                                batteryLevelTextView.setText("Battery: N/A"); // Handle error case for app UI
                            }
                            model.clearGlassesBatteryPrefix();
                            // Log.e(TAG, "Error getting battery level", e);
                        }
                    } else {
                        stopBatteryMonitoringForGlasses(); // Calls updateBatteryDisplayVisibility(false) indirectly or directly
                        if (batteryLevelTextView != null) {
                            batteryLevelTextView.setText("Battery: --%"); // Reset app UI when not connected
                        }
                        model.clearGlassesBatteryPrefix();
                    }
                }
            };
        }
        batteryUpdateHandler.removeCallbacks(batteryUpdateRunnable);
        batteryUpdateHandler.post(batteryUpdateRunnable);
        updateBatteryDisplayVisibility(true);
    }

    private void stopBatteryMonitoringForGlasses() {
        if (batteryUpdateHandler != null && batteryUpdateRunnable != null) {
            batteryUpdateHandler.removeCallbacks(batteryUpdateRunnable);
        }
        updateBatteryDisplayVisibility(false);
        // Don't reset lastReportedBatteryLevelForGlasses here, so next connect can compare
    }


    @Override
    public void onResume() {
        super.onResume();
        if (ultralite != null && ultralite.getConnected().getValue() != null && ultralite.getConnected().getValue()) {
            startBatteryMonitoringForGlasses();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopBatteryMonitoringForGlasses();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopBatteryMonitoringForGlasses();
        // batteryUpdateRunnable = null; // Can be set to null
    }
}