package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
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
            String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
            String data = update.getCallbackQuery().getData();

            if (votes.containsKey(userId)) {
                // відповідаємо як "попап" без спаму
                try {
                    execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                            .callbackQueryId(update.getCallbackQuery().getId())
                            .text("Ви вже проголосували ✅")
                            .showAlert(false)
                            .build());
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
                return;
            }

            votes.put(userId, data);

            // відповідаємо попапом
            try {
                execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                        .callbackQueryId(update.getCallbackQuery().getId())
                        .text("Ваш голос прийнято: " + data)
                        .showAlert(false)
                        .build());
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendOrUpdatePollMessage(String chatId) {
        StringBuilder sb = new StringBuilder("Голосування:\n");
        for (String option : options) {
            sb.append(option).append(": ").append(votes.values().stream().filter(v -> v.equals(option)).count())
                    .append(" голосів\n");
        }

        // клавіатура
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
                // відправляємо нове повідомлення
                var sentMessage = execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(sb.toString())
                        .replyMarkup(markup)
                        .build());
                messageIdWithPoll = sentMessage.getMessageId();
            } else {
                // редагуємо існуюче
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

    // Авто-оновлення кожні 30 секунд
    private void updatePoll() {
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
        executor.scheduleAtFixedRate(bot::updatePoll, 5, 5, TimeUnit.SECONDS);
    }
}