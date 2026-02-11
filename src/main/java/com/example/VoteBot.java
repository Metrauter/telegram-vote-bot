package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoteBot extends TelegramLongPollingBot {

    private static final String GROUP_CHAT_ID = "-1003860160178";

    private static final Set<Long> ADMIN_IDS = Set.of(
            875558201L,
            636575553L
    );

    private final Map<Long, String> votes = new HashMap<>();
    private final List<String> options = new ArrayList<>();
    private Integer messageIdWithPoll = null;

    @Override
    public String getBotUsername() {
        return "PavlogradVoteBot";
    }

    @Override
    public String getBotToken() {
        return "8529535908:AAGghyNIcLwiHhJ4XKSSeDGeS5mPK9sIp4M";
    }

    @Override
    public void onUpdateReceived(Update update) {

        // --- Команди у приваті ---
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            Long userId = update.getMessage().getFrom().getId();

            if (text.startsWith("/startpoll")) {
                if (!ADMIN_IDS.contains(userId)) {
                    sendMessage(chatId, "Тільки адміністратори можуть запускати опитування.");
                    return;
                }

                String[] parts = text.split(" ", 2);
                if (parts.length < 2) {
                    sendMessage(chatId,
                            "Вкажіть варіанти через крапку з комою:\n/startpoll Варіант1;Варіант2;Варіант3");
                    return;
                }

                options.clear();
                for (String option : parts[1].split(";")) {
                    options.add(option.trim());
                }

                votes.clear();
                sendOrUpdatePollMessage(GROUP_CHAT_ID);
                sendMessage(chatId, "Опитування запущено ✅ (результати оновлюються у групі)");
            }
        }

        // --- Голоси через кнопки ---
        if (update.hasCallbackQuery()) {
            Long userId = update.getCallbackQuery().getFrom().getId();
            String callbackId = update.getCallbackQuery().getId();
            String data = update.getCallbackQuery().getData();

            // Перевірка підписки на канал
            if (!isUserSubscribed(userId)) {
                // Якщо користувач вже голосував, видаляємо його голос
                votes.remove(userId);
                sendOrUpdatePollMessage(GROUP_CHAT_ID);

                try {
                    execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                            .callbackQueryId(callbackId)
                            .text("Ви не підписані на канал! Ваш голос скасовано.")
                            .showAlert(true)
                            .build());
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
                return;
            }

            if (votes.containsKey(userId)) {
                try {
                    execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                            .callbackQueryId(callbackId)
                            .text("Ви вже проголосували ✅")
                            .showAlert(false)
                            .build());
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
                return;
            }

            votes.put(userId, data);

            try {
                execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackId)
                        .text("Ваш голос прийнято: " + data)
                        .showAlert(false)
                        .build());
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    // --- Перевірка підписки користувача ---
    private boolean isUserSubscribed(Long userId) {
        try {
            GetChatMember getChatMember = new GetChatMember(GROUP_CHAT_ID, userId);
            ChatMember member = execute(getChatMember);
            // статус "member", "administrator", "creator" — всі вважаються підписниками
            return member instanceof ChatMemberAdministrator || member instanceof ChatMemberOwner ||
                    "member".equals(member.getStatus()) || "creator".equals(member.getStatus());
        } catch (TelegramApiException e) {
            return false;
        }
    }

    // --- Відправка або оновлення одного повідомлення з кнопками і результатами ---
    private void sendOrUpdatePollMessage(String chatId) {
        StringBuilder sb = new StringBuilder("Голосування:\n");
        for (String option : options) {
            long count = votes.values().stream().filter(v -> v.equals(option)).count();
            sb.append(option).append(": ").append(count).append(" голосів\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String option : options) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(option);
            button.setCallbackData(option);
            rows.add(Collections.singletonList(button));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        try {
            if (messageIdWithPoll == null) {
                var sentMessage = execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(sb.toString())
                        .replyMarkup(markup)
                        .build());
                messageIdWithPoll = sentMessage.getMessageId();
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(chatId);
                edit.setMessageId(messageIdWithPoll);
                edit.setText(sb.toString());
                edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // --- Періодичне оновлення результатів та перевірка підписок ---
    private void updatePoll() {
        // Видаляємо голоси користувачів, які відписалися
        List<Long> toRemove = new ArrayList<>();
        for (Long userId : votes.keySet()) {
            if (!isUserSubscribed(userId)) {
                toRemove.add(userId);
            }
        }
        for (Long id : toRemove) votes.remove(id);

        if (messageIdWithPoll != null) {
            sendOrUpdatePollMessage(GROUP_CHAT_ID);
        }
    }

    private void sendMessage(String chatId, String text) {
        try {
            execute(new SendMessage(chatId, text));
        } catch (TelegramApiException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        VoteBot bot = new VoteBot();
        botsApi.registerBot(bot);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        // Перевірка підписок і оновлення результатів кожні 10 секунд
        executor.scheduleAtFixedRate(bot::updatePoll, 10, 10, TimeUnit.SECONDS);
    }
}