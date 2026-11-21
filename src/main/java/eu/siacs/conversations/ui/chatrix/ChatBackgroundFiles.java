package eu.siacs.conversations.ui.chatrix;

import java.io.File;
import java.util.function.Predicate;

/**
 * Chatrix.One: file names of the per-chat background pictures and the clean-up of pictures whose
 * chat is gone. A chat is only ever archived, but deleting an account deletes its chats, and their
 * pictures would stay in private storage. Plain Java without Android, so unit tests can check it.
 */
public final class ChatBackgroundFiles {

    private static final String PREFIX = "chatrix_chat_background_";
    private static final String SUFFIX = ".jpg";

    /** Younger files are left alone: their chat may not be in the database yet. */
    static final long MIN_AGE_MS = 60L * 60 * 1000;

    private ChatBackgroundFiles() {}

    /** File name of the picture of this chat. */
    static String name(final String conversationUuid) {
        return PREFIX + conversationUuid + SUFFIX;
    }

    /** The chat uuid in a per-chat picture's file name, or null for any other file. */
    static String uuidOf(final String fileName) {
        if (fileName == null
                || !fileName.startsWith(PREFIX)
                || !fileName.endsWith(SUFFIX)
                || fileName.length() <= PREFIX.length() + SUFFIX.length()) {
            return null;
        }
        return fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
    }

    /**
     * Deletes per-chat pictures in {@code dir} whose chat does not exist. If {@code chatExists}
     * throws (database trouble), nothing more is deleted. Returns the number of deleted files.
     */
    static int sweep(final File dir, final Predicate<String> chatExists, final long now) {
        final File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int deleted = 0;
        for (final File file : files) {
            final String uuid = uuidOf(file.getName());
            if (uuid == null || !file.isFile() || now - file.lastModified() < MIN_AGE_MS) {
                continue;
            }
            if (!chatExists.test(uuid) && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }
}
