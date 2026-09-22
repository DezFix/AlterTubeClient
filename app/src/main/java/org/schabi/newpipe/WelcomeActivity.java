package org.schabi.newpipe;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.databinding.ActivityWelcomeBinding;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.ThemeHelper;

/**
 * Single first-run screen with the two setup questions
 * (update checker + theme) instead of a stack of popups.
 */
public class WelcomeActivity extends AppCompatActivity {
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> proceedToMain());

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        Localization.assureCorrectAppLanguage(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.setDayNightMode(this);
        ThemeHelper.setTheme(this);

        final ActivityWelcomeBinding binding =
                ActivityWelcomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.welcomeStartButton.setOnClickListener(v -> {
            final SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(this);
            final SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean(getString(R.string.update_app_key),
                    binding.welcomeUpdatesSwitch.isChecked());

            final int checkedId = binding.welcomeThemeGroup.getCheckedRadioButtonId();
            final String themeValue;
            if (checkedId == binding.welcomeThemeLight.getId()) {
                themeValue = getString(R.string.light_theme_key);
            } else if (checkedId == binding.welcomeThemeDark.getId()) {
                themeValue = getString(R.string.dark_theme_key);
            } else {
                themeValue = getString(R.string.auto_device_theme_key);
            }
            editor.putString(getString(R.string.theme_key), themeValue);
            editor.putInt("isFirstRun", 1);
            editor.apply();

            // System notification permission (Android 13+), then go to main screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS);
            } else {
                proceedToMain();
            }
        });
    }

    private void proceedToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
