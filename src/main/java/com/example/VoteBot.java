package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoteBot extends TelegramLongPollingBot {

    private static final String GROUP_CHAT_ID = "-1003467071058";
//            "-1003860160178"; Test Group

    private static final Set<Long> ADMIN_IDS = Set.of(
            875558201L,
            636575553L
    );

    private final Map<Long, String> votes = new HashMap<>();
    private final List<String> options = new ArrayList<>();

    private Integer messageIdWithPoll = null;
    private boolean pollActive = false;

    @Override
    public String getBotUsername() {
        return "PavlogradVoteBot";
    }

    @Override
    public String getBotToken() {
        return "8529535908:AAGXGC14Nodj8Kx1hlTT7FNi7-MManOsE3I";
    }

    @Override
    public void onUpdateReceived(Update update) {

        // --- Команди ---
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            Long userId = update.getMessage().getFrom().getId();

            // --- START POLL ---
            if (text.startsWith("/startpoll")) {

                if (!ADMIN_IDS.contains(userId)) {
                    sendMessage(chatId, "Тільки адміністратори можуть запускати опитування.");
                    return;
                }

                String[] parts = text.split(" ", 2);
                if (parts.length < 2) {
                    sendMessage(chatId,
                            "Вкажіть варіанти через ;\n/startpoll Варіант1;Варіант2;Варіант3");
                    return;
                }

                options.clear();
                for (String option : parts[1].split(";")) {
                    options.add(option.trim());
                }

                votes.clear();
                pollActive = true;
                messageIdWithPoll = null;

                sendOrUpdatePollMessage(GROUP_CHAT_ID);
                sendMessage(chatId, "Опитування запущено ✅");
            }

            // --- STOP POLL ---
            if (text.equals("/stoppoll")) {

                if (!ADMIN_IDS.contains(userId)) {
                    sendMessage(chatId, "Тільки адміністратори можуть зупиняти опитування.");
                    return;
                }

                if (!pollActive) {
                    sendMessage(chatId, "Немає активного голосування.");
                    return;
                }

                pollActive = false;
                stopPoll();
                sendMessage(chatId, "Голосування завершено ✅");
            }
        }

        // --- CALLBACK (ГОЛОСИ) ---
        if (update.hasCallbackQuery()) {

            Long userId = update.getCallbackQuery().getFrom().getId();
            String callbackId = update.getCallbackQuery().getId();
            String data = update.getCallbackQuery().getData();

            if (!pollActive) {
                answer(callbackId, "Голосування завершено.", false);
                return;
            }

            if (!isUserSubscribed(userId)) {
                votes.remove(userId);
                sendOrUpdatePollMessage(GROUP_CHAT_ID);
                answer(callbackId, "Ви не підписані. Голос скасовано.", true);
                return;
            }

            if (votes.containsKey(userId)) {
                answer(callbackId, "Ви вже проголосували ✅", false);
                return;
            }

            votes.put(userId, data);
            sendOrUpdatePollMessage(GROUP_CHAT_ID);
            answer(callbackId, "Ваш голос прийнято: " + data, false);
        }
    }

    // --- Завершення голосування ---
    private void stopPoll() {

        if (messageIdWithPoll == null) return;

        StringBuilder sb = new StringBuilder("🏁 Голосування завершено\n\n");

        for (String option : options) {
            long count = votes.values().stream()
                    .filter(v -> v.equals(option))
                    .count();
            sb.append(option).append(": ").append(count).append(" голосів\n");
        }

        EditMessageText edit = new EditMessageText();
        edit.setChatId(GROUP_CHAT_ID);
        edit.setMessageId(messageIdWithPoll);
        edit.setText(sb.toString());

        try {
            execute(edit); // без кнопок
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // --- Перевірка підписки ---
    private boolean isUserSubscribed(Long userId) {
        try {
            GetChatMember getChatMember = new GetChatMember(GROUP_CHAT_ID, userId);
            ChatMember member = execute(getChatMember);
            return !"left".equals(member.getStatus());
        } catch (TelegramApiException e) {
            return false;
        }
    }

    // --- Відправка/оновлення ---
    private void sendOrUpdatePollMessage(String chatId) {

        if (!pollActive) return;

        StringBuilder sb = new StringBuilder("📊 Кращий гравець січня\n\n");

        for (String option : options) {
            long count = votes.values().stream()
                    .filter(v -> v.equals(option))
                    .count();
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

    private void answer(String callbackId, String text, boolean alert) {
        try {
            execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .showAlert(alert)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(String chatId, String text) {
        try {
            execute(new SendMessage(chatId, text));
        } catch (TelegramApiException ignored) {}
    }

    // --- Авто перевірка відписок ---
    private void updatePoll() {

        if (!pollActive) return;

        List<Long> toRemove = new ArrayList<>();

        for (Long userId : votes.keySet()) {
            if (!isUserSubscribed(userId)) {
                toRemove.add(userId);
            }
        }

        for (Long id : toRemove) votes.remove(id);

        if (!toRemove.isEmpty()) {
            sendOrUpdatePollMessage(GROUP_CHAT_ID);
        }
    }

    public static void main(String[] args) throws Exception {

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        VoteBot bot = new VoteBot();
        botsApi.registerBot(bot);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(bot::updatePoll, 10, 10, TimeUnit.SECONDS);
    }
}
