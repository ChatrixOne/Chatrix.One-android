package eu.siacs.conversations.ui.fragment.settings;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import eu.siacs.conversations.ui.chatrix.ChatBackground;
import eu.siacs.conversations.ui.chatrix.QuickActions;
import eu.siacs.conversations.ui.AddReactionActivity;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.material.color.DynamicColors;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.Maps;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import eu.siacs.conversations.ui.util.SettingsUtils;
import im.conversations.android.model.AttachmentChoice;
import java.util.Map;

public class InterfaceSettingsFragment extends XmppPreferenceFragment {

    private static final Map<AttachmentChoice.Type, AttachmentChoice> QUICK_ACTIONS =
            Maps.immutableEnumMap(
                    Maps.uniqueIndex(
                            Collections2.filter(
                                    ConversationFragment.ATTACHMENT_CHOICES,
                                    ac -> ac != null && ac.quickAction()),
                            AttachmentChoice::type));

    // Chatrix.One: pick an own picture as chat background
    private final ActivityResultLauncher<PickVisualMediaRequest> pickChatBackground =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(), this::onChatBackgroundPicked);

    private void onChatBackgroundPicked(@Nullable final Uri uri) {
        if (uri == null) {
            return;
        }
        final var context = requireContext().getApplicationContext();
        new Thread(
                        () -> {
                            final boolean ok = ChatBackground.save(context, uri);
                            final var activity = getActivity();
                            if (activity == null) {
                                return;
                            }
                            activity.runOnUiThread(
                                    () -> {
                                        if (!ok) {
                                            Toast.makeText(
                                                            context,
                                                            R.string.chatrix_background_failed,
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                            return;
                                        }
                                        final ListPreference background =
                                                findPreference(ChatBackground.PREF_KEY);
                                        if (background != null) {
                                            background.setValue(ChatBackground.CUSTOM);
                                        }
                                    });
                        })
                .start();
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_interface, rootKey);
        // Chatrix.One: app language (stored by AndroidX, not in our preferences)
        final ListPreference language = findPreference("chatrix_language");
        if (language != null) {
            final var values = eu.siacs.conversations.ui.chatrix.AppLanguage.values(requireContext());
            language.setEntryValues(values.toArray(new CharSequence[0]));
            language.setEntries(
                    eu.siacs.conversations.ui.chatrix.AppLanguage.entries(
                            requireContext(), values));
            language.setValue(
                    eu.siacs.conversations.ui.chatrix.AppLanguage.chosen(requireContext()));
            language.setSummary(language.getEntry());
            // same dialog as on the welcome screen, with the note about machine translation
            language.setOnPreferenceClickListener(
                    preference -> {
                        eu.siacs.conversations.ui.chatrix.AppLanguage.showPicker(
                                requireContext());
                        return true;
                    });
        }
        // Chatrix.One: quick reaction emoji is chosen from the full emoji picker
        final Preference quickEmoji = findPreference(QuickActions.PREF_QUICK_EMOJI);
        if (quickEmoji != null) {
            quickEmoji.setOnPreferenceClickListener(
                    p -> {
                        startActivity(
                                new Intent(requireContext(), AddReactionActivity.class)
                                        .putExtra(AddReactionActivity.EXTRA_PICK_QUICK_EMOJI, true));
                        return true;
                    });
        }
        final ListPreference chatBackground = findPreference(ChatBackground.PREF_KEY);
        if (chatBackground != null) {
            chatBackground.setOnPreferenceChangeListener(
                    (preference, newValue) -> {
                        if (ChatBackground.CUSTOM.equals(newValue)) {
                            // only switch to "own picture" once a picture was picked and saved
                            pickChatBackground.launch(
                                    new PickVisualMediaRequest.Builder()
                                            .setMediaType(
                                                    ActivityResultContracts.PickVisualMedia
                                                            .ImageOnly.INSTANCE)
                                            .build());
                            return false;
                        }
                        return true;
                    });
        }
        final var themePreference = findPreference(AppSettings.THEME);
        final var dynamicColors = findPreference(AppSettings.DYNAMIC_COLORS);
        final ListPreference quickAction = findPreference(AppSettings.QUICK_ACTION);
        if (themePreference == null || dynamicColors == null || quickAction == null) {
            throw new IllegalStateException(
                    "The preference resource file did not contain theme or color preferences");
        }
        themePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof final String theme) {
                final int desiredNightMode = AppSettings.getDesiredNightMode(theme);
                requireSettingsActivity().setDesiredNightMode(desiredNightMode);
            }
            return true;
        });
        dynamicColors.setVisible(DynamicColors.isDynamicColorAvailable());
        dynamicColors.setOnPreferenceChangeListener((preference, newValue) -> {
            requireSettingsActivity().setDynamicColors(Boolean.TRUE.equals(newValue));
            return true;
        });
        quickAction.setSummaryProvider(new QuickActionSummaryProvider());
        final var quickActionsEntryValues = new CharSequence[QUICK_ACTIONS.size()];
        final var quickActionEntries = new CharSequence[QUICK_ACTIONS.size()];
        int i = 0;
        for (final var entry : QUICK_ACTIONS.entrySet()) {
            quickActionsEntryValues[i] = entry.getKey().toString();
            quickActionEntries[i] = getString(entry.getValue().name());
            ++i;
        }
        quickAction.setEntryValues(quickActionsEntryValues);
        quickAction.setEntries(quickActionEntries);
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        if (key.equals(AppSettings.ALLOW_SCREENSHOTS)) {
            SettingsUtils.applyScreenshotSetting(requireActivity());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_interface);
        final Preference quickEmoji = findPreference(QuickActions.PREF_QUICK_EMOJI);
        if (quickEmoji != null) {
            quickEmoji.setSummary(QuickActions.quickEmoji(requireContext()));
        }
    }

    public SettingsActivity requireSettingsActivity() {
        final var activity = requireActivity();
        if (activity instanceof SettingsActivity settingsActivity) {
            return settingsActivity;
        }
        throw new IllegalStateException(
                String.format(
                        "%s is not %s",
                        activity.getClass().getName(), SettingsActivity.class.getName()));
    }

    private static class QuickActionSummaryProvider
            implements Preference.SummaryProvider<ListPreference> {

        @Nullable
        @Override
        public CharSequence provideSummary(@NonNull ListPreference preference) {
            final var value = preference.getValue();
            if (Strings.isNullOrEmpty(value)) {
                return null;
            }
            final AttachmentChoice.Type type;
            try {
                type = AttachmentChoice.Type.valueOf(value);
            } catch (final IllegalArgumentException e) {
                return null;
            }
            final var attachmentChoice = QUICK_ACTIONS.get(type);
            if (attachmentChoice == null) {
                return null;
            }
            return preference.getContext().getString(attachmentChoice.name());
        }
    }
}
