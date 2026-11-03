package eu.siacs.conversations.ui.fragment.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.primitives.Ints;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.TimeFrameUtils;

public abstract class XmppPreferenceFragment extends PreferenceFragmentCompat {

    private final SharedPreferences.OnSharedPreferenceChangeListener
            sharedPreferenceChangeListener =
                    (sharedPreferences, key) -> {
                        if (key == null) {
                            return;
                        }
                        if (isAdded()) {
                            onSharedPreferenceChanged(key);
                        }
                    };

    protected void onSharedPreferenceChanged(@NonNull String key) {
        Log.d(Config.LOGTAG, "onSharedPreferenceChanged(" + key + ")");
    }

    public void onBackendConnected() {}

    /** Chatrix.One: show list choices in the same rounded Material dialog as the rest of the app. */
    @Override
    public void onDisplayPreferenceDialog(@NonNull final Preference preference) {
        if (preference instanceof ListPreference listPreference
                && listPreference.getEntries() != null
                && listPreference.getEntryValues() != null) {
            final int checked = listPreference.findIndexOfValue(listPreference.getValue());
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(listPreference.getDialogTitle())
                    .setSingleChoiceItems(
                            listPreference.getEntries(),
                            checked,
                            (dialog, which) -> {
                                final String value =
                                        listPreference.getEntryValues()[which].toString();
                                if (listPreference.callChangeListener(value)) {
                                    listPreference.setValue(value);
                                }
                                dialog.dismiss();
                            })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (preference instanceof EditTextPreference editTextPreference) {
            final var input = new com.google.android.material.textfield.TextInputEditText(requireContext());
            input.setText(editTextPreference.getText());
            input.setSingleLine(true);
            final var layout = new com.google.android.material.textfield.TextInputLayout(requireContext());
            final int pad = (int) (24 * getResources().getDisplayMetrics().density);
            layout.setPadding(pad, pad / 3, pad, 0);
            layout.addView(input);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(editTextPreference.getDialogTitle())
                    .setMessage(editTextPreference.getDialogMessage())
                    .setView(layout)
                    .setPositiveButton(
                            R.string.ok,
                            (dialog, which) -> {
                                final String value =
                                        input.getText() == null ? "" : input.getText().toString();
                                if (editTextPreference.callChangeListener(value)) {
                                    editTextPreference.setText(value);
                                }
                            })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        final var sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) {
            sharedPreferences.registerOnSharedPreferenceChangeListener(
                    this.sharedPreferenceChangeListener);
        }
        final var xmppActivity = requireXmppActivity();
        if (xmppActivity.xmppConnectionService != null) {
            this.onBackendConnected();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        final var sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    this.sharedPreferenceChangeListener);
        }
    }

    protected void reconnectAccounts() {
        final var service = requireService();
        for (final Account account : service.getAccounts()) {
            if (account.isEnabled()) {
                service.reconnectAccountInBackground(account);
            }
        }
    }

    protected XmppActivity requireXmppActivity() {
        final var activity = requireActivity();
        if (activity instanceof XmppActivity xmppActivity) {
            return xmppActivity;
        }
        throw new IllegalStateException();
    }

    protected XmppConnectionService requireService() {
        final var xmppActivity = requireXmppActivity();
        final var service = xmppActivity.xmppConnectionService;
        if (service != null) {
            return service;
        }
        throw new IllegalStateException();
    }

    protected void runOnUiThread(final Runnable runnable) {
        requireActivity().runOnUiThread(runnable);
    }

    protected static String timeframeValueToName(final Context context, final int value) {
        if (value == 0) {
            return context.getString(R.string.never);
        } else {
            return TimeFrameUtils.resolve(context, 1000L * value);
        }
    }

    protected void setValues(
            final ListPreference listPreference,
            @ArrayRes int resId,
            final Function<Integer, String> valueToName) {
        final int[] choices = getResources().getIntArray(resId);
        final CharSequence[] entries = new CharSequence[choices.length];
        final CharSequence[] entryValues = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; ++i) {
            final int value = choices[i];
            entryValues[i] = String.valueOf(choices[i]);
            entries[i] = valueToName.apply(value);
        }
        listPreference.setEntries(entries);
        listPreference.setEntryValues(entryValues);
        listPreference.setSummaryProvider(
                new Preference.SummaryProvider<ListPreference>() {
                    @Nullable
                    @Override
                    public CharSequence provideSummary(@NonNull ListPreference preference) {
                        final Integer value =
                                Ints.tryParse(Strings.nullToEmpty(preference.getValue()));
                        return valueToName.apply(value == null ? 0 : value);
                    }
                });
    }
}
