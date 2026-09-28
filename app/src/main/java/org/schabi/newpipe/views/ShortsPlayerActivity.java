package org.schabi.newpipe.views;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.fragments.BlankFragment;
import org.schabi.newpipe.views.shorts.VerticalShortsFragment;

/** Fullscreen host for the vertical player. */
public class ShortsPlayerActivity extends AppCompatActivity {

    private VerticalShortsFragment shortsFragment;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        setTheme(R.style.Base_V19_BlackTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shorts_player);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        if (savedInstanceState == null) {
            shortsFragment = VerticalShortsFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.shorts_player_container, shortsFragment)
                    .commit();
        } else {
            final androidx.fragment.app.Fragment existing =
                    getSupportFragmentManager().findFragmentById(R.id.shorts_player_container);
            if (existing instanceof VerticalShortsFragment) {
                shortsFragment = (VerticalShortsFragment) existing;
            }
        }
    }

    /** Switches to the regular player for the current vertical video. */
    public void openCurrentAsVideo() {
        if (shortsFragment != null) {
            shortsFragment.openAsVideo();
        }
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        final WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }
}
