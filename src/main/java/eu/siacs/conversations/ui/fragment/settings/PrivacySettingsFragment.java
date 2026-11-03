package eu.siacs.conversations.ui.fragment.settings;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.chatrix.UpdateCheckWorker;

public class PrivacySettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_privacy, rootKey);
        // Chatrix.One: the update notice only exists for website installs
        final var updateCheck = findPreference(UpdateCheckWorker.PREF_KEY);
        if (updateCheck != null) {
            updateCheck.setVisible(UpdateCheckWorker.isAvailable(requireContext()));
        }
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.READ_RECEIPTS,
                    AppSettings.BROADCAST_LAST_ACTIVITY,
                    AppSettings.ALLOW_MESSAGE_CORRECTION ->
                    requireService().refreshAllPresences();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_privacy);
    }
}
