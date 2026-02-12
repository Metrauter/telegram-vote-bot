package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
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
import java.util.concurrent.ConcurrentHashMap;

public class VoteBot extends TelegramLongPollingBot {

    private static final String GROUP_CHAT_ID = "-1003467071058";

    private static final Set<Long> ADMIN_IDS = Set.of(
            875558201L,
            636575553L
    );

    // userId -> optionIndex
    private final Map<Long, Integer> votes = new ConcurrentHashMap<>();
    private final List<String> options = Collections.synchronizedList(new ArrayList<>());

    private volatile Integer pollMessageId = null;
    private volatile boolean pollActive = false;

    @Override
    public String getBotUsername() {
        return "PavlogradVoteBot";
    }

    @Override
    public String getBotToken() {
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new RuntimeException("BOT_TOKEN is not set!");
        }
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            handleMessage(update);
        }

        if (update.hasCallbackQuery()) {
            handleCallback(update);
        }
    }

    private void handleMessage(Update update) {

        String text = update.getMessage().getText();
        String chatId = update.getMessage().getChatId().toString();
        Long userId = update.getMessage().getFrom().getId();

        if (text.startsWith("/startpoll")) {

            if (!ADMIN_IDS.contains(userId)) {
                send(chatId, "❌ Тільки адміністратори можуть запускати голосування.");
                return;
            }

            String[] parts = text.split(" ", 2);
            if (parts.length < 2) {
                send(chatId, "Формат:\n/startpoll Варіант1;Варіант2;Варіант3");
                return;
            }

            String[] rawOptions = parts[1].split(";");
            if (rawOptions.length < 2) {
                send(chatId, "Потрібно мінімум 2 варіанти.");
                return;
            }

            synchronized (options) {
                options.clear();
                for (String option : rawOptions) {
                    options.add(option.trim());
                }
            }

            votes.clear();
            pollActive = true;
            pollMessageId = null;

            renderPoll();
            send(chatId, "✅ Голосування запущено!");
        }

        if (text.equals("/stoppoll")) {

            if (!ADMIN_IDS.contains(userId)) {
                send(chatId, "❌ Тільки адміністратори можуть зупиняти голосування.");
                return;
            }

            if (!pollActive) {
                send(chatId, "Немає активного голосування.");
                return;
            }

            pollActive = false;
            renderFinalResults();
            send(chatId, "🏁 Голосування завершено!");
        }
    }

    private void handleCallback(Update update) {

        Long userId = update.getCallbackQuery().getFrom().getId();
        String callbackId = update.getCallbackQuery().getId();
        String data = update.getCallbackQuery().getData();

        if (!pollActive) {
            answer(callbackId, "Голосування завершено.", false);
            return;
        }

        if (!data.startsWith("vote_")) {
            answer(callbackId, "Помилка.", false);
            return;
        }

        if (!isUserSubscribed(userId)) {
            votes.remove(userId);
            renderPoll();
            answer(callbackId, "Ви не підписані на групу.", true);
            return;
        }

        if (votes.containsKey(userId)) {
            answer(callbackId, "Ви вже проголосували ✅", false);
            return;
        }

        int optionIndex = Integer.parseInt(data.substring(5));

        if (optionIndex < 0 || optionIndex >= options.size()) {
            answer(callbackId, "Невірний вибір.", false);
            return;
        }

        votes.put(userId, optionIndex);

        renderPoll();
        answer(callbackId, "Ваш голос прийнято ✅", false);
    }

    private void renderPoll() {

        if (!pollActive) return;

        StringBuilder text = new StringBuilder("📊 Кращий гравець січня\n\n");

        Map<Integer, Integer> counts = countVotes();

        synchronized (options) {
            for (int i = 0; i < options.size(); i++) {
                int count = counts.getOrDefault(i, 0);
                text.append(options.get(i))
                        .append(": ")
                        .append(count)
                        .append(" голосів\n");
            }
        }

        InlineKeyboardMarkup markup = buildKeyboard();

        try {
            if (pollMessageId == null) {
                var sent = execute(SendMessage.builder()
                        .chatId(GROUP_CHAT_ID)
                        .text(text.toString())
                        .replyMarkup(markup)
                        .build());

                pollMessageId = sent.getMessageId();
            } else {
                execute(EditMessageText.builder()
                        .chatId(GROUP_CHAT_ID)
                        .messageId(pollMessageId)
                        .text(text.toString())
                        .replyMarkup(markup)
                        .build());
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void renderFinalResults() {

        if (pollMessageId == null) return;

        StringBuilder text = new StringBuilder("🏁 Голосування завершено\n\n");

        Map<Integer, Integer> counts = countVotes();

        synchronized (options) {
            for (int i = 0; i < options.size(); i++) {
                int count = counts.getOrDefault(i, 0);
                text.append(options.get(i))
                        .append(": ")
                        .append(count)
                        .append(" голосів\n");
            }
        }

        try {
            execute(EditMessageText.builder()
                    .chatId(GROUP_CHAT_ID)
                    .messageId(pollMessageId)
                    .text(text.toString())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private Map<Integer, Integer> countVotes() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Integer index : votes.values()) {
            counts.merge(index, 1, Integer::sum);
        }
        return counts;
    }

    private InlineKeyboardMarkup buildKeyboard() {

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        synchronized (options) {
            for (int i = 0; i < options.size(); i++) {
                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText(options.get(i));
                button.setCallbackData("vote_" + i);
                rows.add(Collections.singletonList(button));
            }
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private boolean isUserSubscribed(Long userId) {
        try {
            ChatMember member = execute(new GetChatMember(GROUP_CHAT_ID, userId));
            return !"left".equals(member.getStatus());
        } catch (TelegramApiException e) {
            return false;
        }
    }

    private void answer(String callbackId, String text, boolean alert) {
        try {
            execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .showAlert(alert)
                    .build());
        } catch (TelegramApiException ignored) {}
    }

    private void send(String chatId, String text) {
        try {
            execute(new SendMessage(chatId, text));
        } catch (TelegramApiException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new VoteBot());
    }
}