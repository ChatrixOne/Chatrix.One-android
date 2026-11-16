package eu.siacs.conversations.ui.fragment.settings;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;

public class PrivacySettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_privacy, rootKey);
        final var googlePushPreference = findPreference(AppSettings.GOOGLE_PUSH);
        if (googlePushPreference == null) {
            throw new IllegalStateException("The preference resource file is missing preferences");
        }
        googlePushPreference.setEnabled(
                Conversations.getInstance(requireContext()).isPlayServicesAvailable());
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.READ_RECEIPTS,
                    AppSettings.BROADCAST_LAST_ACTIVITY,
                    AppSettings.ALLOW_MESSAGE_CORRECTION ->
                    requireService().refreshAllPresences();
            case AppSettings.GOOGLE_PUSH -> toggleGooglePush();
        }
    }

    private void toggleGooglePush() {
        final var enabled = new AppSettings(requireContext()).isGooglePush();
        if (enabled) {
            requireService().refreshAllFcmTokens();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_privacy);
    }
}
