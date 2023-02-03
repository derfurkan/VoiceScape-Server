package de.furkan.voicescape.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.sleep;

public class Core {

  private static final Core instance = new Core();

  public final boolean KILL_SOCKET_IF_INVALID_MESSAGE = true;

  public final int MIN_LOGIN_TIMEOUT_MS = 5_000,
      LOGIN_SPAM_BLACKLIST_BAN_MS = 60_000,
      MIN_MESSAGE_TIMEOUT_MS = 500,
      MESSAGE_SPAM_BLACKLIST_BAN_MS = 20_000,
      MESSAGE_THREAD_WAIT_TIME_MS = 2_000,
      FLAG_REMOVE_TIMEOUT_MS = 5_000,
      REGISTRATION_TIMEOUT_MS = 5_000;
  public final int VOICE_SERVER_PORT = 24444, MESSAGE_SERVER_PORT = 25555;

  public ArrayList<VoiceThread> voiceSockets = new ArrayList<>();
  public ArrayList<String> registeredPlayerSockets = new ArrayList<>();
  public ArrayList<String> blackListedSpamIPs = new ArrayList<>();
  public HashMap<String, Long> lastLoginIPs = new HashMap<>();

  public static Core getInstance() {
    return instance;
  }

  public static void main(String[] args) {
    new Server();
  }
}

class Server {

  public Server() {
    ServerSocket voiceServer, messageServer;
    try {
      voiceServer = new ServerSocket(Core.getInstance().VOICE_SERVER_PORT);
      messageServer = new ServerSocket(Core.getInstance().MESSAGE_SERVER_PORT);
      System.out.println("-- Server started --");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    while (true) {
      try {
        AtomicReference<Socket> messageSocket = new AtomicReference<>(null);
        Socket voiceSocket = voiceServer.accept();

        if (Core.getInstance()
            .blackListedSpamIPs
            .contains(voiceSocket.getInetAddress().getHostAddress())) {
          voiceSocket.close();
          continue;
        }

        if (Core.getInstance().lastLoginIPs.get(voiceSocket.getInetAddress().getHostAddress())
                != null
            && System.currentTimeMillis()
                    - Core.getInstance()
                        .lastLoginIPs
                        .get(voiceSocket.getInetAddress().getHostAddress())
                < Core.getInstance().MIN_LOGIN_TIMEOUT_MS) {
          System.out.println(
              "["
                  + voiceSocket.getInetAddress().getHostAddress()
                  + "] Login spam detected, blacklisting");
          Core.getInstance().blackListedSpamIPs.add(voiceSocket.getInetAddress().getHostAddress());

          new Timer()
              .schedule(
                  new TimerTask() {
                    @Override
                    public void run() {
                      Core.getInstance()
                          .blackListedSpamIPs
                          .remove(voiceSocket.getInetAddress().getHostAddress());
                    }
                  },
                  Core.getInstance().LOGIN_SPAM_BLACKLIST_BAN_MS);
          voiceSocket.close();
          continue;
        }

        Core.getInstance()
            .lastLoginIPs
            .put(voiceSocket.getInetAddress().getHostAddress(), System.currentTimeMillis());

        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        Thread thread =
            new Thread(
                () -> {
                  Thread messageConnectionThread =
                      new Thread(
                          () -> {
                            try {
                              messageSocket.set(messageServer.accept());
                              completableFuture.complete(true);
                            } catch (IOException e) {
                              e.printStackTrace();
                            }
                          });
                  messageConnectionThread.start();

                  try {
                    sleep(Core.getInstance().MESSAGE_THREAD_WAIT_TIME_MS);
                  } catch (InterruptedException e) {

                  }

                  if (messageSocket.get() == null) {
                    messageConnectionThread.interrupt();
                    System.out.println(
                        "["
                            + voiceSocket.getInetAddress().getHostAddress()
                            + "] Didn't connect to message server, disconnecting");
                    try {
                      completableFuture.complete(false);
                      voiceSocket.close();
                    } catch (IOException e) {
                      e.printStackTrace();
                    }
                  }
                });
        thread.start();
        if (!completableFuture.get()) {
          continue;
        }
        thread.interrupt();

        MessageThread messageThread =
            new MessageThread(messageSocket.get(), new VoiceThread(voiceSocket));
        messageThread.currentVoiceThread.messageThread = messageThread;
        new Timer()
            .schedule(
                new TimerTask() {
                  @Override
                  public void run() {
                    if (messageThread.currentVoiceThread.clientName == null
                        || messageThread.currentVoiceThread.clientName.isEmpty()) {
                      System.out.println(
                          "["
                              + messageThread
                                  .currentVoiceThread
                                  .currentSocketConnection
                                  .getInetAddress()
                                  .getHostAddress()
                              + "] Didn't send registration, disconnecting");
                      messageThread.currentVoiceThread.stop();
                      messageThread.stop();
                    }
                  }
                },
                Core.getInstance().REGISTRATION_TIMEOUT_MS);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
