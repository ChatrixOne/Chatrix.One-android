package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.hints.Store;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.reactions.Reactions;
import java.util.Collection;

public class ReactionManager extends AbstractManager {

    private final XmppConnectionService service;

    public ReactionManager(final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    public void processReactions(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Jid counterpart,
            final MessageArchiveManager.Query query) {
        final var isTypeGroupChat =
                packet.getType()
                        == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT;
        final var reactions = packet.getExtension(Reactions.class);
        if (reactions == null) {
            throw new IllegalStateException(
                    "Called processReactions w/o checking if packet has any");
        }
        final var account = getAccount();
        final var conversation = this.service.find(account, counterpart.asBareJid());
        final var user = getManager(MultiUserChatManager.class).getMucUser(packet, query);
        final String reactingTo = reactions.getId();
        if (conversation == null || reactingTo == null) {
            return;
        }
        if (isTypeGroupChat && conversation.getMode() == Conversational.MODE_MULTI) {
            final var mucOptions =
                    getManager(MultiUserChatManager.class).getOrCreateState(conversation);
            final var occupant =
                    mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
            final var occupantId = occupant == null ? null : occupant.getId();
            if (occupantId != null) {
                final boolean isReceived = user == null || !mucOptions.isOurAccount(user);
                final Message message;
                final var inMemoryMessage = conversation.findMessageWithServerMsgId(reactingTo);
                if (inMemoryMessage != null) {
                    message = inMemoryMessage;
                } else {
                    message =
                            this.getDatabase().getMessageWithServerMsgId(conversation, reactingTo);
                }
                if (message != null) {
                    // Chatrix.One: remember what this occupant had before, to notify on new ones
                    final var previous =
                            ImmutableSet.copyOf(
                                    Collections2.transform(
                                            Collections2.filter(
                                                    message.getReactions(),
                                                    r -> occupantId.equals(r.occupantId)),
                                            r -> r.reaction));
                    final var combinedReactions =
                            Reaction.withOccupantId(
                                    message.getReactions(),
                                    reactions.getReactions(),
                                    isReceived,
                                    counterpart,
                                    user == null ? null : user.getRealJid(),
                                    occupantId);
                    message.setReactions(combinedReactions);
                    this.getDatabase().updateMessage(message, false);
                    this.service.updateConversationUi();
                    if (isReceived) {
                        chatrixNotify(
                                message,
                                query,
                                counterpart.getResource(),
                                previous,
                                reactions.getReactions());
                    }
                } else {
                    Log.d(Config.LOGTAG, "message with id " + reactingTo + " not found");
                }
            } else {
                Log.d(Config.LOGTAG, "received reaction in channel w/o occupant ids. ignoring");
            }
        } else {
            final Message message;
            final var inMemoryMessage =
                    conversation.findMessageWithUuidOrRemoteId(reactingTo, null, null);
            if (inMemoryMessage != null) {
                message = inMemoryMessage;
            } else {
                message = this.getDatabase().getMessageWithUuidOrRemoteId(conversation, reactingTo);
            }
            if (message == null) {
                Log.d(Config.LOGTAG, "message with id " + reactingTo + " not found");
                return;
            }
            final boolean isReceived;
            final Jid reactionFrom;
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                Log.d(Config.LOGTAG, "received reaction as MUC PM. triggering validation");
                final var mucOptions =
                        getManager(MultiUserChatManager.class).getOrCreateState(conversation);
                final var occupant =
                        mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
                final var occupantId = occupant == null ? null : occupant.getId();
                if (occupantId == null) {
                    Log.d(
                            Config.LOGTAG,
                            "received reaction via PM channel w/o occupant ids. ignoring");
                    return;
                }
                isReceived = user == null || !mucOptions.isOurAccount(user);
                if (isReceived) {
                    reactionFrom = counterpart;
                } else {
                    if (!occupantId.equals(message.getOccupantId())) {
                        Log.d(
                                Config.LOGTAG,
                                "reaction received via MUC PM did not pass validation");
                        return;
                    }
                    reactionFrom = account.getJid().asBareJid();
                }
            } else {
                if (packet.fromAccount(account)) {
                    isReceived = false;
                    reactionFrom = account.getJid().asBareJid();
                } else {
                    isReceived = true;
                    reactionFrom = counterpart;
                }
            }
            // Chatrix.One: remember what this sender had before, to notify on new ones
            final var fromBare = reactionFrom.asBareJid();
            final var previous =
                    ImmutableSet.copyOf(
                            Collections2.transform(
                                    Collections2.filter(
                                            message.getReactions(),
                                            r -> fromBare.equals(r.from.asBareJid())),
                                    r -> r.reaction));
            final var combinedReactions =
                    Reaction.withFrom(
                            message.getReactions(),
                            reactions.getReactions(),
                            isReceived,
                            reactionFrom);
            message.setReactions(combinedReactions);
            this.service.updateMessage(message, false);
            if (isReceived) {
                chatrixNotify(
                        message,
                        query,
                        conversation.getMode() == Conversational.MODE_MULTI
                                ? counterpart.getResource()
                                : null,
                        previous,
                        reactions.getReactions());
            }
        }
    }

    // Chatrix.One: notify about reactions newly added to one of our own messages. Like messages,
    // only live stanzas and the offline catch-up count; older history from the archive never
    // notifies. Removed or repeated reactions never notify.
    private void chatrixNotify(
            final Message message,
            final MessageArchiveManager.Query query,
            final String from,
            final Collection<String> previous,
            final Collection<String> current) {
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            return;
        }
        if (query != null && !query.isCatchup()) {
            return;
        }
        final var added =
                ImmutableSet.copyOf(Collections2.filter(current, r -> !previous.contains(r)));
        if (added.isEmpty()) {
            return;
        }
        this.service.getNotificationService().pushReaction(message, from, added);
    }

    public boolean sendReactions(final Message message, final Collection<String> reactions) {
        final Conversation conversation;
        if (message.getConversation() instanceof Conversation c) {
            conversation = c;
        } else {
            return false;
        }
        final var isPrivateMessage = message.isPrivateMessage();
        final Jid reactTo;
        final boolean typeGroupChat;
        final String reactToId;
        final Collection<Reaction> combinedReactions;
        if (conversation.getMode() == Conversational.MODE_MULTI && !isPrivateMessage) {
            final var mucOptions = conversation.getMucOptions();
            if (!mucOptions.participating()) {
                Log.e(Config.LOGTAG, "not participating in MUC");
                return false;
            }
            final var self = mucOptions.getSelf();
            final String occupantId = self.getOccupantId();
            if (Strings.isNullOrEmpty(occupantId)) {
                Log.e(Config.LOGTAG, "occupant id not found for reaction in MUC");
                return false;
            }
            final var existingRaw =
                    ImmutableSet.copyOf(
                            Collections2.transform(message.getReactions(), r -> r.reaction));
            final var reactionsAsExistingVariants =
                    ImmutableSet.copyOf(
                            Collections2.transform(
                                    reactions, r -> Emoticons.existingVariant(r, existingRaw)));
            if (!reactions.equals(reactionsAsExistingVariants)) {
                Log.d(Config.LOGTAG, "modified reactions to existing variants");
            }
            reactToId = message.getServerMsgId();
            reactTo = conversation.getAddress().asBareJid();
            typeGroupChat = true;
            combinedReactions =
                    Reaction.withOccupantId(
                            message.getReactions(),
                            reactionsAsExistingVariants,
                            false,
                            self.getFullJid(),
                            conversation.getAccount().getJid(),
                            occupantId);
        } else {
            reactToId = message.getMessageId();
            typeGroupChat = false;
            if (isPrivateMessage) {
                reactTo = message.getCounterpart();
            } else {
                reactTo = conversation.getAddress().asBareJid();
            }
            combinedReactions =
                    Reaction.withFrom(
                            message.getReactions(),
                            reactions,
                            false,
                            conversation.getAccount().getJid());
        }
        if (reactTo == null || Strings.isNullOrEmpty(reactToId)) {
            Log.e(Config.LOGTAG, "could not find id to react to");
            return false;
        }
        final var reactionMessage = reaction(reactTo, typeGroupChat, reactToId, reactions);
        this.connection.sendMessagePacket(reactionMessage);
        message.setReactions(combinedReactions);
        this.getDatabase().updateMessage(message, false);
        this.service.updateConversationUi();
        return true;
    }

    private static im.conversations.android.xmpp.model.stanza.Message reaction(
            final Jid to,
            final boolean groupChat,
            final String reactingTo,
            final Collection<String> ourReactions) {
        final im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        packet.setType(
                groupChat
                        ? im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT
                        : im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
        packet.setTo(to);
        final var reactions = packet.addExtension(new Reactions());
        reactions.setId(reactingTo);
        for (final String ourReaction : ourReactions) {
            reactions.addExtension(
                    new im.conversations.android.xmpp.model.reactions.Reaction(ourReaction));
        }
        packet.addExtension(new Store());
        return packet;
    }
}
