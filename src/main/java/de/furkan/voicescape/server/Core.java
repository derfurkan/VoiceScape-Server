package de.furkan.voicescape.server;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Core {

  private static final Core instance = new Core();
  public static VoiceServerThread voiceServerThread;
  public final boolean KILL_SOCKET_IF_INVALID_MESSAGE = true;
  public final int MIN_LOGIN_TIMEOUT_MS = 5_000,
      LOGIN_SPAM_BLACKLIST_BAN_MS = 60_000,
      MIN_MESSAGE_TIMEOUT_MS = 500,
      MESSAGE_SPAM_BLACKLIST_BAN_MS = 20_000,
      FLAG_REMOVE_TIMEOUT_MS = 5_000,
      REGISTRATION_TIMEOUT_MS = 5_000,
      MAX_THREADS_PER_POOL = 20,
      UPDATE_CLIENTS_INTERVAL_MS = 10_000,
      MAX_CLIENTS = 50_000,
      MAX_CLIENTS_PER_IP = 5;
  public final int VOICE_SERVER_PORT = 24444, MESSAGE_SERVER_PORT = 23333;
  public ArrayList<String> registeredPlayerSockets = new ArrayList<>();
  public ArrayList<String> unregisteredPlayerSockets = new ArrayList<>();
  public ArrayList<String> blackListedSpamIPs = new ArrayList<>();
  public HashMap<String, Long> lastLoginIPs = new HashMap<>();
  public ArrayList<ThreadPoolExecutor> threadPools = new ArrayList<>();
  public ArrayList<ClientThread> clientThreads = new ArrayList<>();

  public static Core getInstance() {
    return instance;
  }

  public static void main(String[] args) {
    new UpdateThread();
    new Server();
  }

  public void sendToAllClientThreads(String message) {
    ArrayList<ClientThread> clientThreads = new ArrayList<>(this.clientThreads);
    for (ClientThread clientThread : clientThreads) {
      clientThread.out.println(message);
    }
  }

  static class Server {

    public Server() {
      voiceServerThread = new VoiceServerThread();
      ServerSocket messageServer;
      try {
        messageServer = new ServerSocket(Core.getInstance().MESSAGE_SERVER_PORT);
        System.out.println("-- Server started --");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      while (true) {

        try {
          Socket messageSocket = messageServer.accept();
          if (Core.getInstance().registeredPlayerSockets.size() >= Core.getInstance().MAX_CLIENTS) {
            messageSocket.close();
            continue;
          }

          int clientsPerIP = 0;
          for (ClientThread clientThread : Core.getInstance().clientThreads) {
            if (clientThread
                .currentConnection
                .getInetAddress()
                .getHostAddress()
                .equals(messageSocket.getInetAddress().getHostAddress())) {
              clientsPerIP++;
            }
          }
          if (clientsPerIP >= Core.getInstance().MAX_CLIENTS_PER_IP) {
            System.out.println(
                "["
                    + messageSocket.getInetAddress().getHostAddress()
                    + "] Max clients per IP reached, closing connection");
            messageSocket.close();
            continue;
          }
          messageSocket.setTcpNoDelay(true);

          if (Core.getInstance()
              .blackListedSpamIPs
              .contains(messageSocket.getInetAddress().getHostAddress())) {
            messageSocket.close();
            continue;
          }

          if (Core.getInstance().lastLoginIPs.get(messageSocket.getInetAddress().getHostAddress())
                  != null
              && System.currentTimeMillis()
                      - Core.getInstance()
                          .lastLoginIPs
                          .get(messageSocket.getInetAddress().getHostAddress())
                  < Core.getInstance().MIN_LOGIN_TIMEOUT_MS) {
            System.out.println(
                "["
                    + messageSocket.getInetAddress().getHostAddress()
                    + "] Login spam detected, blacklisting");
            Core.getInstance()
                .blackListedSpamIPs
                .add(messageSocket.getInetAddress().getHostAddress());

            new Timer()
                .schedule(
                    new TimerTask() {
                      @Override
                      public void run() {
                        Core.getInstance()
                            .blackListedSpamIPs
                            .remove(messageSocket.getInetAddress().getHostAddress());
                      }
                    },
                    Core.getInstance().LOGIN_SPAM_BLACKLIST_BAN_MS);
            messageSocket.close();
            continue;
          }

          Core.getInstance()
              .lastLoginIPs
              .put(messageSocket.getInetAddress().getHostAddress(), System.currentTimeMillis());

          ThreadPoolExecutor threadPoolExecutor = getThreadPool();

          ClientThread clientThread =
              new ClientThread(messageSocket, threadPoolExecutor, voiceServerThread.voiceServer);
          threadPoolExecutor.execute(clientThread);

          new Timer()
              .schedule(
                  new TimerTask() {
                    @Override
                    public void run() {
                      if (clientThread.clientName == null || clientThread.clientName.isEmpty()) {
                        System.out.println(
                            "["
                                + clientThread.currentConnection.getInetAddress().getHostAddress()
                                + "] Didn't send registration, disconnecting");
                        clientThread.stop();
                      }
                    }
                  },
                  Core.getInstance().REGISTRATION_TIMEOUT_MS);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    private ThreadPoolExecutor getThreadPool() {
      ThreadPoolExecutor threadPoolExecutor = null;
      for (ThreadPoolExecutor threadPool : Core.getInstance().threadPools) {
        if (threadPool.getActiveCount() + 1 < Core.getInstance().MAX_THREADS_PER_POOL) {
          threadPoolExecutor = threadPool;
          break;
        }
      }
      if (threadPoolExecutor == null) {
        threadPoolExecutor =
            new ThreadPoolExecutor(
                Core.getInstance().MAX_THREADS_PER_POOL,
                Core.getInstance().MAX_THREADS_PER_POOL,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>());
        Core.getInstance().threadPools.add(threadPoolExecutor);
        System.out.println("-- Created new thread pool --");
      }

      return threadPoolExecutor;
    }
  }
}
