package com.mattfeury.saucillator.android;

import java.util.ArrayList;

import com.mattfeury.saucillator.android.instruments.*;
import com.mattfeury.saucillator.android.settings.ModifyInstrument;
import com.mattfeury.saucillator.android.settings.Settings;
import com.mattfeury.saucillator.android.sound.*;
import com.mattfeury.saucillator.android.utilities.Utilities;
import com.mattfeury.saucillator.android.visuals.*;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.Window;
import android.view.View.OnTouchListener;
import android.widget.Toast;

/*
 * Main activity for the App. This will spin up the visuals and the audio engine. 
 * The audio engine gets its own thread.
 */
public class SauceEngine extends Activity implements OnTouchListener {
    private static final String TAG = "Sauce";
    private SauceView view;

    public enum Modes {
      EDIT {
        public String toString() {
          return "Edit Instrument";
        }
      },
      PLAY_MULTI {
        public String toString() {
          return "Multi Instrument";
        }
      }
    }
    private Modes mode = Modes.EDIT;

    //defaults
    private int note = 0;
    private int octave = 4;

    //music shtuffs
    public int[] scale = Theory.pentatonicScale;

    public final static int DELAY_MAX = UGen.SAMPLE_RATE; //Is this right?
    public final static int MOD_RATE_MAX = 20;
    public final static int MOD_DEPTH_MAX = 1000;

    public final static float DEFAULT_LAG = 0.5f;
    public static int TRACKPAD_GRID_SIZE = 12;

    private boolean init = false;
    
    private final int maxFingers = 5;
    // which finger ID corresponds to which instrument
    // maybe make "Fingerable" interface... lolol
    private Object[] fingersById = new Object[maxFingers];

    //synth elements
    private Dac dac;
    private Looper looper;
    private ParametricEQ eq;
    private ComplexOsc[] oscillatorsById = new ComplexOsc[maxFingers];
    
    // The currentOscillator is never actually heard
    // It is kept as a template and updated anytime an instrument is edited/created
    // We create a deepCopy of it for actually playing.
    private static ComplexOsc currentOscillator;

    MediaPlayer secretSauce;
    private Vibrator vibrator;
    private boolean canVibrate = false;
    private int VIBRATE_SPEED = 100; //in ms
    private SubMenu instrumentMenu;
    private final int instrumentMenuId = 9;
    public static final String DATA_FOLDER = "/sdcard/sauce/";
    public static final int MODIFY_ACTION = 1;
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "Brewing sauce...");

        super.onCreate(savedInstanceState);

        secretSauce = MediaPlayer.create(this, R.raw.sauceboss);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);
        view = (SauceView)findViewById(R.id.sauceview);
        view.setOnTouchListener(this);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        
        //Default
        currentOscillator = InstrumentManager.getInstrument(getAssets(), "Sine");
        
        if (vibrator != null)
          canVibrate = true;
        
        Thread t = new Thread() {
      	  public void run() {
      	    try {
      	    	dac = new Dac();
      	    	looper = new Looper();

      	    	eq = new ParametricEQ();
      	    	eq.chuck(dac);
      	    	looper.chuck(eq);

      	    	dac.open();
              init = true;
              setupParamHandlers();
              Log.i(TAG, "Sauce ready.");

      	      while (true) {
        	    	dac.tick();
      	      }
      	    }
      	    catch(Exception ex) {
      	    	ex.printStackTrace();
      	    	Log.e(TAG, "bad time " + ex.toString());
      	    	dac.close();
      	    }
      	  }
      	};
      	
      t.start();
    }

    protected void onDestroy() {
    	android.os.Process.killProcess(android.os.Process.myPid());
    }
    
    // Maintains landscape mode
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
      super.onConfigurationChanged(newConfig);
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    public boolean isFingered(Object obj) {
      for (int i = 0; i < fingersById.length; i++)
        if (obj.equals(fingersById[i]))
          return true;

      return false;
    }

    /**
     * That main goodness. Handles touch events and gets properties of them to change the oscillators
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
      if (! init) return false;

      int maxHeight = v.getMeasuredHeight();
      int maxWidth = v.getMeasuredWidth();

      int action = event.getAction();
      int actionCode = action & MotionEvent.ACTION_MASK;
      
      if (actionCode == MotionEvent.ACTION_UP && dac.isPlaying()) { //last finger lifted. stop playback
        for (int i = 0; i < fingersById.length; i++) {
          ComplexOsc osc = oscillatorsById[i];
          if (osc != null && osc.isPlaying() && ! osc.isReleasing())
            osc.togglePlayback();

          fingersById[i] = null;
        }

        view.clearFingers();
        return true;
      }

      int pointerCount = event.getPointerCount();
      if (pointerCount == 5) {
        secretSauce.start(); //the secret sauce
        return true;
      }
    
      /*
       * Loop through each finger.
       * We go backwards because of the buttons. If a button is pressed and held, the next finger call
       * will be a POINTER_DOWN call, but the first button will interpret it first since it has a smaller index. That's bad.
       */
      for (int i = pointerCount - 1; i > -1; i--) {
        int id = event.getPointerId(i);
        float y = event.getY(i);
        float x = event.getX(i);

        final int actionIndex = event.getActionIndex();
        final int actionId = event.getPointerId(actionIndex);

        // Finger on main pad. This affects an oscillator or parameter (when in edit mode)
        if (view.isInPad(x,y)) {
          int controllerWidth = (int) (maxWidth * SauceView.controllerWidth);
          float yInverted = maxHeight - y;
          float xScaled = (x - controllerWidth) / (maxWidth - controllerWidth);
          float yScaled = yInverted / maxHeight;

          Object controlled = fingersById[id];
          boolean fingerDefined = controlled != null;
          
          if (mode == Modes.EDIT) {
            // Determine if this edits a parameter. otherwise, edit the oscillator
            DrawableParameter param = view.optParameter(x, y, controlled);

            // Modify the osc (stored as id = 0)
            // TODO fail nicely if this doesn't exist. alert the user to choose an instrument
            int oscId = 0;
            ComplexOsc osc = getOrCreateOscillator(oscId);

            // If this is on a parameter AND
            // this finger isn't controlling something or it's controlling this param)
            if (param != null && (! fingerDefined || param.equals(controlled))) {
              // Modify the parameter
              if (! fingerDefined)
                fingersById[id] = param;

              param.set(xScaled, yScaled);
              view.invalidate();
            } else if (osc != null && (osc.equals(controlled) || (! fingerDefined && ! isFingered(osc)))) {
              // Update oscillator
              fingersById[id] = osc;
              handleTouchForOscillator(id, v, event);
            }
          } else if (mode == Modes.PLAY_MULTI) {

            // Determine which synth this finger corresponds to
            ComplexOsc osc = getOrCreateOscillator(id);

            if (! fingerDefined)
              fingersById[id] = osc;

            handleTouchForOscillator(id, v, event);
          }

          if (actionCode == MotionEvent.ACTION_POINTER_UP) {
            fingersById[actionId] = null;
          }
        } else {
          //controller buttons
          //TODO FIXME there needs to be a more abstracted way to do these

          int buttonHeight = maxHeight / SauceView.numButtons;

          final int upIndex = event.getActionIndex();
          if ((actionCode == MotionEvent.ACTION_POINTER_DOWN && upIndex == i)
              || actionCode == MotionEvent.ACTION_DOWN) {

            if (canVibrate)
              vibrator.vibrate(VIBRATE_SPEED);

          	if (y <= buttonHeight) {
              //Toggle Looper Button
              boolean isRecording = looper.toggleRecording();
              if (isRecording)
              	view.focusLooper();
              else
              	view.unfocusLooper();

            } else if (y <= buttonHeight * 2) {
              //Undo Looper Button
              looper.undo();
              view.unfocusLooper();
            } else {
              //Reset Looper Button
              looper.reset();
              view.unfocusLooper();
            }
          }
        }
      }

      return true; // indicate event was handled
    }

    private void setupParamHandlers() {
      ComplexOsc osc = getOrCreateOscillator(0);

      view.resetParams();
      
      DrawableParameter eqParam = new DrawableParameter(
            new ParameterHandler() {
              public void updateParameter(float x, float y) {
                //FIXME yuck
                x = Utilities.unscale(x, SauceView.controllerWidth, 1);

                eq.setFrequency(x * x);
                eq.setQ(y * y);
              }
            },
            eq.getFrequency(), //frequency on x
            eq.getQ() // q on y
          );

      DrawableParameter lfoParam = new DrawableParameter(
            new ParameterHandler() {
              public void updateParameter(float x, float y) {
                //FIXME yuck
                x = Utilities.unscale(x, SauceView.controllerWidth, 1);

                ComplexOsc osc = getOrCreateOscillator(0);

                // Set the template and the playing oscillator
                // We do this so we don't have to recreate a new oscillator everytime
                // a param is changed. Since we used deep copies, that would probably hurt performance.
                currentOscillator.setModRate((int)(x * MOD_RATE_MAX));
                currentOscillator.setModDepth((int)(y * MOD_DEPTH_MAX));
                osc.setModRate((int)(x * MOD_RATE_MAX));
                osc.setModDepth((int)(y * MOD_DEPTH_MAX));
                
                Log.i(TAG, "LFO rate : " + x + " / depth: " + y);
              }
            },
            osc.getModRate() / (float)MOD_RATE_MAX, // mod rate on x
            osc.getModDepth() / (float)MOD_DEPTH_MAX, // mod depth on y
            MOD_RATE_MAX,
            MOD_DEPTH_MAX
          );

      // Delay: rate on x, decay on y
      DrawableParameter delayParam = new DrawableParameter(
            new ParameterHandler() {
              public void updateParameter(float x, float y) {
                //FIXME yuck... still
                x = Utilities.unscale(x, SauceView.controllerWidth, 1);

                ComplexOsc osc = getOrCreateOscillator(0);

                // Set the template and the playing oscillator
                // We do this so we don't have to recreate a new oscillator everytime
                // a param is changed. Since we used deep copies, that would probably hurt performance.
                currentOscillator.setDelayRate((int)(x * DELAY_MAX));
                currentOscillator.setDelayDecay(y);
                osc.setDelayRate((int)(x * DELAY_MAX));
                osc.setDelayDecay(y);
              }
            },
            osc.getDelayRate() / (float)DELAY_MAX,
            osc.getDelayDecay(),
            DELAY_MAX,
            1
          );
      
      view.addParam(eqParam);
      view.addParam(lfoParam);
      view.addParam(delayParam);
    }

    /**
     * Oscillator handlers
     */
    public void handleTouchForOscillator(int id, View v, MotionEvent event) {
      ComplexOsc osc = getOrCreateOscillator(id);

      if (osc == null) return;

      final int index = event.findPointerIndex(id);
      final int action = event.getAction();
      final int actionCode = action & MotionEvent.ACTION_MASK;
      final int actionIndex = event.getActionIndex();
      final int actionId = event.getPointerId(actionIndex);

      final float y = event.getY(index);
      final float x = event.getX(index);
      final int height = v.getMeasuredHeight();
      final int width = v.getMeasuredWidth();
      final int controllerWidth = (int) (width * SauceView.controllerWidth);
      final float yInverted = height - y;
      final float xScaled = (x - controllerWidth) / (width - controllerWidth);
      final float yScaled = yInverted / height;
 
      if (actionCode == MotionEvent.ACTION_POINTER_DOWN || actionCode == MotionEvent.ACTION_DOWN || actionCode == MotionEvent.ACTION_MOVE) {
        view.updateOrCreateFinger(id, event.getX(index), event.getY(index), event.getSize(index), event.getPressure(index));

        //play if we were stopped
        if(! osc.isPlaying())
          osc.togglePlayback();
        else if (osc.isReleasing())
          osc.startAttack();

        updateFrequency(id, (int)(yScaled * TRACKPAD_GRID_SIZE));
        updateAmplitude(id, xScaled);
      } else if (actionCode == MotionEvent.ACTION_POINTER_UP && actionId == id) {
        //finger up. kill the osc
        view.removeFinger(id);

        if(osc.isPlaying() && ! osc.isReleasing())
          osc.togglePlayback();
      }
      
    }

    public static ComplexOsc getCurrentOscillator() {
      return currentOscillator;
    }
    public void resetOscillators() {
      for (int i = 0; i < oscillatorsById.length; i++) {
        ComplexOsc osc = oscillatorsById[i];
        if (osc != null)
          disconnectOsc(osc);

        oscillatorsById[i] = null;
      }

    }
    public ComplexOsc optOscillator(int id) {
      return oscillatorsById[id];
    }
    public ComplexOsc getOrCreateOscillator(int id) {
      ComplexOsc osc = oscillatorsById[id];
      if (osc != null)
        return osc;

      Oscillator copy = InstrumentManager.copyInstrument(currentOscillator);
      if (copy != null)
        osc = (ComplexOsc) copy;
      else {
        osc = InstrumentManager.getInstrument(getAssets(), "Sine");
        Toast.makeText(this, "Error: Unable to duplicate instrument", Toast.LENGTH_SHORT).show();
      }

      connectOsc(osc);
      oscillatorsById[id] = osc;
      
      return osc;
    }
    public void updateAmplitude(int id, float amp) {
      ComplexOsc osc = optOscillator(id);

      if (osc != null)
        osc.setAmplitude(amp);
    }      
    public void updateFrequency(int id, int offset) {
      ComplexOsc osc = optOscillator(id);

      if (osc != null)
        osc.setFreqByOffset(scale, offset);
    }

    // Update oscillators based on the settings parameters.
    private void updateOscSettings() {
      float newFreq = Theory.getFrequencyForNote(note + 1, octave);
      for (ComplexOsc osc : oscillatorsById)
        if (osc != null)
          osc.setBaseFreq(newFreq);
    }
    
    /**
     * Settings handlers
     */
    private boolean launchSettings() {
    	Intent intent = new Intent(SauceEngine.this, Settings.class);
    	intent.putExtra("octave", octave);
    	intent.putExtra("note", note);
    	intent.putExtra("file name", WavWriter.filePrefix);
    	intent.putExtra("visuals", view.getVisuals());
    	startActivityForResult(intent, 0);
    	return true;
    }
    private boolean editInstrument() {
    	Intent intent = new Intent(SauceEngine.this, ModifyInstrument.class);
    	intent.putExtra("createNew", false);
    	startActivityForResult(intent, SauceEngine.MODIFY_ACTION);
    	return true;
    }
    // Called when settings activity ends. Updates proper params
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
      if (requestCode == 0 && data != null) {
        Bundle extras = data.getExtras();

        if (extras != null) {
          WavWriter.filePrefix = extras.getString("file name");
          note = extras.getInt("note");
          octave = extras.getInt("octave");
          view.setVisuals(extras.getBoolean("visuals"));
          updateOscSettings();
        }
      } else if (requestCode == SauceEngine.MODIFY_ACTION) {
        Log.d(TAG,"EDITTED INSTRUMENTES");
        currentOscillator = ModifyInstrument.modifying;
        resetOscillators();
        setupParamHandlers();
      }
    }

    /**
     * Menu handlers
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(R.menu.menu, menu);

      // FIXME this is vomit-inducing
      // Make sure that instrument selector is first item in menu
      instrumentMenu = menu.getItem(0).getSubMenu();

      // Set defaults
      MenuItem toggle = menu.findItem(R.id.toggleMode);
      if (mode == Modes.PLAY_MULTI) {
        toggle.setTitle(Modes.EDIT.toString());
      } else {
        toggle.setTitle(Modes.PLAY_MULTI.toString());
      }

      return true;
    }
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
      boolean result = super.onPrepareOptionsMenu(menu);

      // TODO lookup all instruments
      instrumentMenu.clear();

      // TODO set checked based on current
      ArrayList<String> instruments = InstrumentManager.getAllInstrumentNames(getAssets());
      String[] names = instruments.toArray(new String[0]);
      int i = 0;
      for (String name : names) {
        instrumentMenu.add(instrumentMenuId, i, i, name);
        i++;
      }

      instrumentMenu.setGroupCheckable(instrumentMenuId, true, true);
      
      return result;
    }
    public boolean onOptionsItemSelected(MenuItem item) {

    	switch (item.getGroupId()) {
    		case R.id.scales:
    			return scaleSelection(item);
        case instrumentMenuId:
          return instrumentSelection(item, 0); //TODO pass in an id for multi mode
    		default:
          android.util.Log.d("no group id", ""+item.getGroupId());          
    	}
    	switch (item.getItemId()) {
    		case R.id.quit:
    			onDestroy();
    			return true;
    		case R.id.settings:
    			return launchSettings();
    		case R.id.record:
    			return record(item);
        case R.id.toggleMode:
          toggleMode(item);
          return true;
    		default:
    	}
      return false;
    }

    private Modes toggleMode(MenuItem item) {
      Modes other = mode;
      if (mode == Modes.PLAY_MULTI) {
        mode = Modes.EDIT;
      } else {
        mode = Modes.PLAY_MULTI;
      }

      resetOscillators();
      item.setTitle(other.toString());
      Toast.makeText(this, "Switched to " + mode + " Mode.", Toast.LENGTH_SHORT).show();
      return mode;
    }

    private boolean record(MenuItem item) {
      boolean isRecording = dac.toggleRecording();
    	if (isRecording) {
        item.setTitle("Stop Recording");
        item.setIcon(R.drawable.ic_grey_rec);
        Toast.makeText(this, "Recording.", Toast.LENGTH_SHORT).show();
    	}
    	else {
        item.setTitle("Record");
        item.setIcon(R.drawable.ic_rec);
        Toast.makeText(this, "Stopped Recording.", Toast.LENGTH_SHORT).show();

        if(WavWriter.getLastFile() == null)
          return false;
		    	
        Intent intent = new Intent(Intent.ACTION_SEND).setType("audio/*");
	    	intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(WavWriter.getLastFile()));
	    	startActivity(Intent.createChooser(intent, "Share to"));
    	}
      return true;
    }

    private boolean scaleSelection(MenuItem item) {
    	if (item.isChecked()) {
    		return true;
    	}
    	item.setChecked(true);
      int scaleId = item.getItemId();

      switch (scaleId) {
        case R.id.pentatonic: //pentatonic
          scale = Theory.pentatonicScale;
          break;
        case R.id.major: //major
          scale = Theory.majorScale;
          break;
        case R.id.minor: //minor
          scale = Theory.minorScale;
          break;
        case R.id.blues: //blues
          scale = Theory.minorBluesScale;
          break;
        case R.id.chromatic: //chromatic
          scale = Theory.chromaticScale;
          break;
        default:
      }
    	return false;
    }

    private void connectOsc(ComplexOsc osc) {
      osc.chuck(looper);
    }
    private void disconnectOsc(ComplexOsc osc) {
      osc.unchuck(looper);
    }
    private boolean instrumentSelection(MenuItem item, int id) {
    	if (item.isChecked())
    		return true;

      String name = (String) item.getTitle();
      ComplexOsc newOsc = InstrumentManager.getInstrument(getAssets(), name);
      currentOscillator = newOsc;

      if (newOsc == null) {
        Toast.makeText(this, "Bad Instrument.", Toast.LENGTH_SHORT).show();
        return false;
      }

      resetOscillators();

      connectOsc(newOsc);
      oscillatorsById[id] = newOsc;

      updateOscSettings();
      setupParamHandlers();

      return true;
    }
}
