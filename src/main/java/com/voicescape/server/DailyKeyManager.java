package com.voicescape.server;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DailyKeyManager
{
    private static final Logger log = LoggerFactory.getLogger(DailyKeyManager.class);
    private static final int KEY_SIZE_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r ->
    {
        Thread t = new Thread(r, "VoiceScape-KeyRotation");
        t.setDaemon(true);
        return t;
    });

    private volatile byte[] currentKey;
    private volatile Runnable onRotation;

    public DailyKeyManager()
    {
        this.currentKey = generateKey();
    }

    public byte[] getCurrentKey()
    {
        return currentKey;
    }

    public void setOnRotation(Runnable callback)
    {
        this.onRotation = callback;
    }

    public void startRotationSchedule()
    {
        scheduler.scheduleAtFixedRate(() ->
        {
            try
            {
                rotate();
            }
            catch (Exception e)
            {
                log.error("Key rotation failed", e);
            }
        }, ServerConfig.KEY_ROTATION_INTERVAL_MS, ServerConfig.KEY_ROTATION_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("Key rotation scheduled every {}ms", ServerConfig.KEY_ROTATION_INTERVAL_MS);
    }

    public void shutdown()
    {
        scheduler.shutdownNow();
    }

    private void rotate()
    {
        currentKey = generateKey();
        log.info("Daily key rotated");

        if (onRotation != null)
        {
            onRotation.run();
        }
    }

    private byte[] generateKey()
    {
        byte[] key = new byte[KEY_SIZE_BYTES];
        random.nextBytes(key);
        return key;
    }
}
