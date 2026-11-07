package com.voicescape.server;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
public final class UdpCrypto {
 private static final SecureRandom RANDOM = new SecureRandom();
 public static byte[] encrypt(byte[] key, int seq, byte[] data) { return process(key,seq,data,true); }
 public static byte[] decrypt(byte[] key, int seq, byte[] data) { return process(key,seq,data,false); }
 public static byte[] encrypt(byte[] key,int seq,byte[] data,byte[] aad) { return process(key,seq,data,true,aad); }
 public static byte[] decrypt(byte[] key,int seq,byte[] data,byte[] aad) { return process(key,seq,data,false,aad); }
 private static byte[] process(byte[] key,int seq,byte[] data,boolean encrypt) { return process(key,seq,data,encrypt,new byte[0]); }
 private static byte[] process(byte[] key,int seq,byte[] data,boolean encrypt,byte[] aad) {
  try {
   byte[] nonce=new byte[12];
   if(encrypt) RANDOM.nextBytes(nonce);
   else { if(data.length<28) throw new IllegalArgumentException("Short authenticated packet"); System.arraycopy(data,0,nonce,0,12); }
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
   cipher.updateAAD(ByteBuffer.allocate(4).putInt(seq).array());
   cipher.updateAAD(aad);
   byte[] result=cipher.doFinal(data,encrypt?0:12,encrypt?data.length:data.length-12);
   return encrypt?ByteBuffer.allocate(12+result.length).put(nonce).put(result).array():result;
  } catch(Exception e) { throw new IllegalArgumentException("UDP authentication failed",e); }
 }
 public static void processInPlace(SecretKeySpec key,int seq,ByteBuffer in,ByteBuffer out,int mode) {
  byte[] data=new byte[in.remaining()]; in.get(data);
  out.put(process(key.getEncoded(),seq,data,mode==Cipher.ENCRYPT_MODE));
 }
}

