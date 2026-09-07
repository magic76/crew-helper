package com.crewpocket.helper;
import android.content.Context; import android.os.*; import android.speech.tts.*; import java.util.Locale;
/** Local acknowledgement; completes even if TTS is unavailable. */
final class WakeAcknowledgement {
 interface Callback { void onDone(); } private final Handler h=new Handler(Looper.getMainLooper()); private TextToSpeech tts; private boolean ready, released; private Callback pending;
 WakeAcknowledgement(Context c) { tts=new TextToSpeech(c.getApplicationContext(), s->{ ready=s==TextToSpeech.SUCCESS; if(ready) { tts.setLanguage(Locale.TAIWAN); if(pending!=null) speakNow(pending); }}); }
 void speak(String text, Callback cb) { h.post(()->{ if(released){cb.onDone();return;} pending=cb; if(ready) speakNow(cb); else h.postDelayed(()->{if(pending==cb) done(cb);},700); }); }
 private void speakNow(Callback cb) { try { tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){} public void onDone(String id){h.post(()->done(cb));} public void onError(String id){h.post(()->done(cb));}}); if(tts.speak("在呢",TextToSpeech.QUEUE_FLUSH,null,"crew_wake_ack") == TextToSpeech.ERROR) done(cb); else h.postDelayed(()->done(cb),1800); } catch(Exception e){done(cb);} }
 private void done(Callback cb){if(pending!=cb)return; pending=null; try{cb.onDone();}catch(Exception ignored){}}
 void release(){h.post(()->{released=true; try{tts.stop();tts.shutdown();}catch(Exception ignored){} tts=null; if(pending!=null)done(pending);});}
}
