package com.ramandeep.cannyedgedetector;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

public class CameraFragment extends Fragment implements View.OnTouchListener, RadioGroup.OnCheckedChangeListener, View.OnClickListener {
    private static final String TAG = "CameraFragment";
    private static final float SWIPE_RANGE = 75f;

    private RadioGroup mRadioGroup;

    private GestureDetector gestureDetector;
    private GestureDetector.SimpleOnGestureListener simpleOnGestureListener;

    private CameraGLSurfaceView mCameraGLSurfaceView;
    private CameraOperationManager mCameraOperationManager;
    private CameraFrameProcessor mCameraFrameProcessor;

    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor preferenceEditor;

    private DrawerLayout drawerLayout;
    private final DrawerLayout.DrawerListener mDrawerListener = new DrawerLayout.DrawerListener() {
        @Override
        public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {

        }

        @Override
        public void onDrawerOpened(@NonNull View drawerView) {

        }

        @Override
        public void onDrawerClosed(@NonNull View drawerView) {

            //change the values in the camera frame processor
            mCameraFrameProcessor.setBlurRadius(blurRadiusSaved);
            mCameraFrameProcessor.setThreshholdBounds(hMaxSaved, hMinSaved);

            preferenceEditor.putInt("hMaxValue", hMaxSaved);
            preferenceEditor.putInt("hMinValue", hMinSaved);
            preferenceEditor.putFloat("blurRadius", blurRadiusSaved);
            preferenceEditor.commit();
        }

        @Override
        public void onDrawerStateChanged(int newState) {

        }
    };
    private final SeekBar.OnSeekBarChangeListener blurSeekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            //multiply progress by max to set value text
            int progressAdjust = progress;
            if (progress == 0) {
                progressAdjust = 1;
            }
            String valueText = "" + progressAdjust / 2f;
            blurRadiusValueLabel.setText(valueText);
            blurRadiusSaved = progressAdjust / 2f;
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    };
    private final SeekBar.OnSeekBarChangeListener hMaxSeekBarChangeListner = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            //if the max is ever less than or equal to the min value
            //then push the min down by 1 every time since max > min always
            //sets minimum of max bar to 2
            //if progress ever less than 2 set it to  2 since max > min and min > 0
            if(progress < 2){
                hMaxValueSeekBar.setProgress(2);
                return;
            }
            //push down the min bar since min < max
            if (progress <= hMinValueSeekBar.getProgress()) {
                hMinValueSeekBar.setProgress(progress - 1);
            }
            String valueText = "" + progress;
            hMaxValueLabel.setText(valueText);
            hMaxSaved = progress;
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    };

    private final SeekBar.OnSeekBarChangeListener hMinSeekBarChangeListner = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            //if progress is ever the same value as hMax then set the progress to 1 lower
            if (progress >= hMaxValueSeekBar.getProgress()) {
                hMinValueSeekBar.setProgress(hMaxValueSeekBar.getProgress() - 1);
                return;
            }
            //if the progress hits 0 set it to 1 since min > 0
            if(progress < 1){
                hMinValueSeekBar.setProgress(1);
                return;
            }
            String valueText = "" + progress;
            hMinValueLabel.setText(valueText);
            hMinSaved = progress;
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    };

    private AppCompatSeekBar blurRadiusSeekBar;
    private AppCompatSeekBar hMaxValueSeekBar;
    private AppCompatSeekBar hMinValueSeekBar;

    private TextView blurRadiusValueLabel;
    private TextView hMaxValueLabel;
    private TextView hMinValueLabel;

    private int hMinSaved = 0;
    private int hMaxSaved = 0;
    private float blurRadiusSaved = 0;
    private Button mResetDefaultOptionsButton;


    public CameraFragment(DrawerLayout drawerLayout) {
        this.drawerLayout = drawerLayout;
        this.drawerLayout.addDrawerListener(mDrawerListener);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        simpleOnGestureListener = new CustomGestureListener();
        gestureDetector = new GestureDetector(getActivity(), simpleOnGestureListener);

        sharedPreferences = ((Activity) getContext()).getPreferences(Context.MODE_PRIVATE);
        preferenceEditor = sharedPreferences.edit();

        hMaxSaved = sharedPreferences.getInt("hMaxValue", CameraFrameProcessor.DEFAULT_H_MAX);
        hMinSaved = sharedPreferences.getInt("hMinValue", CameraFrameProcessor.DEFAULT_H_MIN);
        blurRadiusSaved = sharedPreferences.getFloat("blurRadius", CameraFrameProcessor.DEFAULT_BLUR_RADIUS);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.camera_frag, container, false);
        mCameraGLSurfaceView = view.findViewById(R.id.displaySurfaceView);

        FragmentActivity activity = getActivity();

        mResetDefaultOptionsButton = activity.findViewById(R.id.optionsResetButton);
        mResetDefaultOptionsButton.setOnClickListener(this);

        mRadioGroup = activity.findViewById(R.id.gradientFilterRadioGroup);
        mRadioGroup.setOnCheckedChangeListener(this);

        blurRadiusSeekBar = activity.findViewById(R.id.blurRadiusSeekBar);
        hMaxValueSeekBar = activity.findViewById(R.id.hMaxValueSeekBar);
        hMinValueSeekBar = activity.findViewById(R.id.hMinValueSeekBar);

        blurRadiusSeekBar.setMax((int) CameraFrameProcessor.MAX_BLUR_RADIUS * 2);//max value is max * step
        //max value is 6 what do i want my step to be?
        //if steps are 0.5 then 12 is max
        hMaxValueSeekBar.setMax(CameraFrameProcessor.MAX_HMAX);
        hMinValueSeekBar.setMax(CameraFrameProcessor.MAX_HMIN);

        blurRadiusSeekBar.setProgress((int) blurRadiusSaved * 2);//current progress is saved value * step, saved value is in float
        hMaxValueSeekBar.setProgress(hMaxSaved);
        hMinValueSeekBar.setProgress(hMinSaved);

        blurRadiusValueLabel = activity.findViewById(R.id.blurRadiusValueLabel);
        hMaxValueLabel = activity.findViewById(R.id.hMaxValueLabel);
        hMinValueLabel = activity.findViewById(R.id.hMinValueLabel);

        blurRadiusValueLabel.setText("" + blurRadiusSaved);
        hMaxValueLabel.setText("" + hMaxSaved);
        hMinValueLabel.setText("" + hMinSaved);

        blurRadiusSeekBar.setOnSeekBarChangeListener(blurSeekBarChangeListener);
        hMaxValueSeekBar.setOnSeekBarChangeListener(hMaxSeekBarChangeListner);
        hMinValueSeekBar.setOnSeekBarChangeListener(hMinSeekBarChangeListner);

        //mCameraGLSurfaceView.setZOrderOnTop(true);
        view.setOnTouchListener(this);
        return view;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CameraOperationManager.CAMERA_CODE && permissions[0].equals(Manifest.permission.CAMERA)) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //Log.i(TAG, "CAMERA_PERMISSION GRANTED!");
            }
        } else {
            getActivity().finish();
        }
    }


    @Override
    public void onStart() {
        super.onStart();
        mCameraFrameProcessor = new CameraFrameProcessor();
        mCameraOperationManager = new CameraOperationManager(getContext());

        mCameraOperationManager.setCamera(CameraOperationManager.BACK_CAMERA);
        mCameraOperationManager.startCameraThread();
        mCameraGLSurfaceView.setCameraOperationManager(mCameraOperationManager);

        mCameraFrameProcessor.init(getContext(), mCameraOperationManager);
        mCameraGLSurfaceView.setCameraFrameProcessor(mCameraFrameProcessor);
    }

    @Override
    public void onResume() {
        super.onResume();
        View decorView = getActivity().getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        mCameraOperationManager.openCamera(getActivity());

        mCameraGLSurfaceView.setSensorRotation(mCameraOperationManager.getCameraOrientation());
        mCameraGLSurfaceView.setDisplayRotation(getActivity().getWindowManager().getDefaultDisplay().getRotation());

        mCameraGLSurfaceView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mCameraOperationManager.closeCamera();
        mCameraGLSurfaceView.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        mCameraOperationManager.stopCameraThread();
        mCameraFrameProcessor.destroy();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return true;
    }

    /**
     * When user selects different gradient filter send the option to camera frame processor
     *
     * @param group
     * @param checkedId
     */
    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        switch (checkedId) {
            case R.id.prewittRadioButton:
                mCameraFrameProcessor.setGradientFilter(CameraFrameProcessor.PREWITT_FILTER_OPTION);
                break;
            case R.id.sobelRadioButton:
                mCameraFrameProcessor.setGradientFilter(CameraFrameProcessor.SOBEL_FILTER_OPTION);
                break;
            case R.id.robertRadioButton:
                mCameraFrameProcessor.setGradientFilter(CameraFrameProcessor.ROBERT_FILTER_OPTION);
                break;
            default:

        }
    }

    @Override
    public void onClick(View v) {
        blurRadiusSeekBar.setProgress(CameraFrameProcessor.DEFAULT_BLUR_RADIUS*2);
        hMaxValueSeekBar.setProgress(CameraFrameProcessor.DEFAULT_H_MAX);
        hMinValueSeekBar.setProgress(CameraFrameProcessor.DEFAULT_H_MIN);

        blurRadiusSaved = CameraFrameProcessor.DEFAULT_BLUR_RADIUS;
        hMaxSaved = CameraFrameProcessor.DEFAULT_H_MAX;
        hMinSaved = CameraFrameProcessor.DEFAULT_H_MIN;
    }


    /**
     * Gesture listener for picking up single screen taps for taking a picture and edge detecting
     * and swipes for returning to camera preview
     */
    private class CustomGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diff = Math.abs(e1.getX() - e2.getX());
            if (diff >= SWIPE_RANGE) {
                mCameraOperationManager.showPreview();
                //System.out.println("Swiped!");
                return true;

            }
            return super.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {

            //System.out.println("Clicked !");
            mCameraOperationManager.takePicture();
            //edge detect this frame
            return true;
        }
    }
}
