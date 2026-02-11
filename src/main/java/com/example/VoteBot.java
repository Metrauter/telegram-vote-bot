package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
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

    // ⚠ Обов'язково заміни на числовий chatId твоєї групи
    private static final String GROUP_CHAT_ID = "123456789";
    private final Map<Long, String> votes = new HashMap<>();
    private final List<String> options = new ArrayList<>();
    private Integer messageIdWithPoll = null;

    @Override
    public String getBotUsername() {
        return System.getenv("PAVLOGRAD_BOT_USERNAME");
    }

    @Override
    public String getBotToken() {
        return System.getenv("PAVLOGRAD_BOT_TOKEN");
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage()) {
            System.out.println("ChatId: " + update.getMessage().getChatId());
        }
        // Команди у приваті
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            Long userId = update.getMessage().getFrom().getId();

            if (text.startsWith("/startpoll")) {
                if (!isUserAdmin(userId)) {
                    sendMessage(chatId, "Тільки адміністратори можуть запускати опитування.");
                    return;
                }

                String[] parts = text.split(" ", 2);
                if (parts.length < 2) {
                    sendMessage(chatId, "Вкажіть варіанти через крапку з комою:\n/startpoll Варіант1;Варіант2;Варіант3");
                    return;
                }

                options.clear();
                for (String option : parts[1].split(";")) {
                    options.add(option.trim());
                }

                votes.clear();
                sendVoteButtons(GROUP_CHAT_ID); // публікація у групу
                sendMessage(chatId, "Опитування запущено ✅ (результати в групі)");
            }
        }

        // Голоси через кнопки
        if (update.hasCallbackQuery()) {
            Long userId = update.getCallbackQuery().getFrom().getId();
            String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
            String data = update.getCallbackQuery().getData();

            if (!isUserSubscribed(userId)) {
                sendMessage(chatId, "Ви не підписані на канал!");
                return;
            }

            if (votes.containsKey(userId)) {
                sendMessage(chatId, "Ви вже проголосували ✅");
                return;
            }

            votes.put(userId, data);
            sendMessage(chatId, "Ваш голос прийнято: " + data);
        }
    }

    private boolean isUserSubscribed(Long userId) {
        try {
            GetChatMember getChatMember = new GetChatMember(GROUP_CHAT_ID, userId);
            var member = execute(getChatMember);
            return member instanceof ChatMemberAdministrator || member instanceof ChatMemberOwner ||
                    "member".equals(member.getStatus());
        } catch (TelegramApiException e) {
            return false;
        }
    }

    private boolean isUserAdmin(Long userId) {
        try {
            GetChatMember getChatMember = new GetChatMember(GROUP_CHAT_ID, userId);
            var member = execute(getChatMember);
            return member instanceof ChatMemberAdministrator || member instanceof ChatMemberOwner;
        } catch (TelegramApiException e) {
            return false;
        }
    }

    private void sendVoteButtons(String chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String option : options) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(option);
            button.setCallbackData(option);
            rows.add(Collections.singletonList(button)); // вертикально
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        SendMessage message = new SendMessage(chatId, "Оберіть варіант:");
        message.setReplyMarkup(markup);

        try {
            var sentMessage = execute(message);
            messageIdWithPoll = sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void updateResults() {
        if (messageIdWithPoll == null) return;

        Map<String, Integer> counts = new HashMap<>();
        for (String vote : votes.values()) {
            counts.put(vote, counts.getOrDefault(vote, 0) + 1);
        }

        StringBuilder sb = new StringBuilder("Результати голосування:\n");
        for (String option : options) {
            sb.append(option).append(": ").append(counts.getOrDefault(option, 0)).append(" голосів\n");
        }

        EditMessageText edit = new EditMessageText();
        edit.setChatId(GROUP_CHAT_ID);
        edit.setMessageId(messageIdWithPoll);
        edit.setText(sb.toString());

        try {
            execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
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

        // Авто-оновлення результатів кожні 30 секунд
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(bot::updateResults, 30, 30, TimeUnit.SECONDS);
    }
}
