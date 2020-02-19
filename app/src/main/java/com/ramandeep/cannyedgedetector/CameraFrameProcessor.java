package com.ramandeep.cannyedgedetector;

import android.content.Context;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptGroup;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicColorMatrix;
import android.renderscript.ScriptIntrinsicConvolve3x3;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Size;
import android.view.Surface;



import java.util.concurrent.Semaphore;

public class CameraFrameProcessor {

    public static final int PREWITT_FILTER_OPTION = 12;
    public static final int SOBEL_FILTER_OPTION = 13;
    public static final int ROBERT_FILTER_OPTION = 14;
    public static final int DEFAULT_H_MAX = 20;
    public static final int DEFAULT_H_MIN = 4;
    public static final int DEFAULT_BLUR_RADIUS = 5;
    public static final float MAX_BLUR_RADIUS = 6f;
    public static final int MAX_HMAX = 50;
    public static final int MAX_HMIN = MAX_HMAX - 1;

    private static final int INITIALIZE_ALLOCS = 1;
    private static final int DISPLAY_SURFACE = 2;
    private static final int NEW_CAMERA_FRAME = 3;
    private static final int EDGE_DETECT = 4;

    private RenderScript mRenderScriptContext;

    private ScriptIntrinsicYuvToRGB intrinsicYuvToRGB;
    private ScriptIntrinsicYuvToRGB intrinsicYuvToRGB_EdgeDetect;
    private ScriptIntrinsicColorMatrix intrinsicGrayScale;
    private ScriptIntrinsicBlur intrinsicBlur;
    private ScriptIntrinsicConvolve3x3 convolve3x3_dx;
    private ScriptIntrinsicConvolve3x3 convolve3x3_dy;
    private ScriptC_CameraFrameProc frameProc;
    private ScriptGroup canny_edge_detector;

    private Allocation camera_input;//create an allocation with that is width x height
    private Allocation edge_detect_input;//an allocation for edge detection input
    private Allocation display_output;//set by calling set output

    private Type flat_type;
    private Type direction_type;
    private Type camera_input_type;//yuv_input
    private Type rgba_output_type;//rgba_output

    private int width;
    private int height;
    private int h_max = 20;
    private int h_min = 4;

    private final float[] PREWITT_FILTER_X =
            {1f,0f,-1f,
            1f,0f,-1f,
            1f,0f,-1f};
    private final float[] PREWITT_FILTER_Y =
            {1f, 1f, 1f,
             0f, 0f, 0f,
            -1f,-1f,-1f};

    private final float[] SOBEL_FILTER_X =
            {1f, 2f, 1f,
             0f, 0f, 0f,
            -1f,-2f,-1f};
    private final float[] SOBEL_FILTER_Y =
            {1f,0f,-1f,
             2f,0f,-2f,
             1f,0f,-1f};

    private final float[] ROBERTS_FILTER_X =
            {0f, 1f, 0f,
             0f, 0f, 0f,
             0f,-1f,0f};
    private final float[] ROBERTS_FILTER_Y =
            {0f,0f, 0f,
             1f,0f,-1f,
             0f,0f, 0f};

    private int mGradientFilterOption = PREWITT_FILTER_OPTION;
    private float[] filter_dx = PREWITT_FILTER_X;
    private float[] filter_dy = PREWITT_FILTER_Y;

    private HandlerThread mHandlerThread;
    private Handler mInitHandler;
    private Handler mFrameIOHandler;

    private static CameraFrameProcessor mCameraFrameProcessor;
    private CameraOperationManager mCameraOperationManager;
    private Semaphore initLock;


    public CameraFrameProcessor(){
        //put the methods below in a background thread
        mHandlerThread = new HandlerThread("RenderScriptInitThread");
        mHandlerThread.start();
        //run initialization
        mInitHandler = new InitHandler(mHandlerThread.getLooper());
        mFrameIOHandler = new FrameIOHandler(mHandlerThread.getLooper());
        initLock = new Semaphore(1);
        //lock until the dimensions are calculated then it will release
        try {
            initLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void init(Context context, CameraOperationManager cameraOperationManager){
        mCameraOperationManager = cameraOperationManager;
        mRenderScriptContext = RenderScript.create(context);
        mInitHandler.sendEmptyMessage(INITIALIZE_ALLOCS);
    }

    private void initTypes() {
        Type.Builder cameraInTypeBuilder = new Type.Builder(mRenderScriptContext, Element.U8(mRenderScriptContext));//yuv is u8
        cameraInTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888);//might be able to ask for rgba_8888
        cameraInTypeBuilder.setX(width);
        cameraInTypeBuilder.setY(height);

        camera_input_type = cameraInTypeBuilder.create();
        rgba_output_type = Type.createXY(mRenderScriptContext,Element.U8_4(mRenderScriptContext),width,height);
        flat_type = Type.createXY(mRenderScriptContext,Element.U8(mRenderScriptContext),width,height);
        direction_type = Type.createXY(mRenderScriptContext, Element.F32(mRenderScriptContext),width,height);

        //yes the same width and height since rotation will be
        //handled by opengl
    }

    private void initAllocations(){
        camera_input = Allocation.createTyped(mRenderScriptContext,camera_input_type,Allocation.USAGE_IO_INPUT | Allocation.USAGE_GRAPHICS_TEXTURE|Allocation.USAGE_SCRIPT);
        edge_detect_input = Allocation.createTyped(mRenderScriptContext,camera_input_type,Allocation.USAGE_IO_INPUT | Allocation.USAGE_GRAPHICS_TEXTURE|Allocation.USAGE_SCRIPT);
        display_output = Allocation.createTyped(mRenderScriptContext,rgba_output_type,Allocation.USAGE_IO_OUTPUT |Allocation.USAGE_SCRIPT);

        camera_input.setOnBufferAvailableListener(new Allocation.OnBufferAvailableListener() {
            @Override
            public void onBufferAvailable(Allocation a) {
                mFrameIOHandler.sendEmptyMessage(NEW_CAMERA_FRAME);
            }
        });

        edge_detect_input.setOnBufferAvailableListener(new Allocation.OnBufferAvailableListener() {
            @Override
            public void onBufferAvailable(Allocation a) {
                mFrameIOHandler.sendEmptyMessage(EDGE_DETECT);
            }
        });

        mCameraOperationManager.addSurface(camera_input.getSurface());
        mCameraOperationManager.addSurface(edge_detect_input.getSurface());
    }

    private void initScripts(){
        //script to convert camera output to rgba
        intrinsicYuvToRGB = ScriptIntrinsicYuvToRGB.create(mRenderScriptContext,Element.U8_4(mRenderScriptContext));
        intrinsicYuvToRGB_EdgeDetect = ScriptIntrinsicYuvToRGB.create(mRenderScriptContext,Element.U8_4(mRenderScriptContext));
        intrinsicBlur = ScriptIntrinsicBlur.create(mRenderScriptContext,Element.U8(mRenderScriptContext));
        intrinsicGrayScale = ScriptIntrinsicColorMatrix.create(mRenderScriptContext);

        convolve3x3_dx = ScriptIntrinsicConvolve3x3.create(mRenderScriptContext,Element.U8(mRenderScriptContext));
        convolve3x3_dy = ScriptIntrinsicConvolve3x3.create(mRenderScriptContext,Element.U8(mRenderScriptContext));

        frameProc = new ScriptC_CameraFrameProc(mRenderScriptContext);

        intrinsicYuvToRGB.setInput(camera_input);
        intrinsicYuvToRGB_EdgeDetect.setInput(edge_detect_input);
        intrinsicBlur.setRadius(DEFAULT_BLUR_RADIUS);

        convolve3x3_dx.setCoefficients(filter_dx);
        convolve3x3_dy.setCoefficients(filter_dy);

        frameProc.set_h_max(h_max);
        frameProc.set_h_min(h_min);

        frameProc.set_camera_x_max(width);
        frameProc.set_camera_y_max(height);

        intrinsicGrayScale.setGreyscale();

        ScriptGroup.Builder2 detector_builder = new ScriptGroup.Builder2(mRenderScriptContext);
        detector_builder.addInput();
        ScriptGroup.Closure yuv_to_rgba = detector_builder.addKernel(intrinsicYuvToRGB_EdgeDetect.getKernelID(),rgba_output_type);
        ScriptGroup.Future rgba_frame = yuv_to_rgba.getReturn();
        //rgba -> grayscale
        ScriptGroup.Closure rgba_to_grayscale = detector_builder.addKernel(intrinsicGrayScale.getKernelID(),rgba_output_type,rgba_frame);
        ScriptGroup.Future grayscale_frame = rgba_to_grayscale.getReturn();
        //grayscale -> grayscale_flat
        ScriptGroup.Closure grayscale_to_gray_flat = detector_builder.addKernel(frameProc.getKernelID_rgba_to_flat(),flat_type,grayscale_frame);
        ScriptGroup.Future flat_gray = grayscale_to_gray_flat.getReturn();
        //gray_flat->blur_flat
        ScriptGroup.Binding blur_input_binding = new ScriptGroup.Binding(intrinsicBlur.getFieldID_Input(),flat_gray);
        ScriptGroup.Closure blur_flat_closure = detector_builder.addKernel(intrinsicBlur.getKernelID(),flat_type,blur_input_binding);
        ScriptGroup.Future blur_flat = blur_flat_closure.getReturn();
        //blur_flat->grad_x
        ScriptGroup.Binding grad_x_input_binding = new ScriptGroup.Binding(convolve3x3_dx.getFieldID_Input(),blur_flat);
        ScriptGroup.Closure grad_x_closure = detector_builder.addKernel(convolve3x3_dx.getKernelID(),flat_type,grad_x_input_binding);
        ScriptGroup.Future grad_x = grad_x_closure.getReturn();

        //blur_flat->grad_y
        ScriptGroup.Binding grad_y_input_binding = new ScriptGroup.Binding(convolve3x3_dy.getFieldID_Input(),blur_flat);
        ScriptGroup.Closure grad_y_closure = detector_builder.addKernel(convolve3x3_dy.getKernelID(),flat_type,grad_y_input_binding);
        ScriptGroup.Future grad_y = grad_y_closure.getReturn();

        //grad_x + grad_y->grad_mag
        ScriptGroup.Closure grad_mag_closure = detector_builder.addKernel(frameProc.getKernelID_gradient_magnitude(),flat_type,grad_x,grad_y);
        ScriptGroup.Future grad_mag = grad_mag_closure.getReturn();

        //grad_x + grad_y->grad_direction
        ScriptGroup.Closure grad_direction_closure = detector_builder.addKernel(frameProc.getKernelID_gradient_direction(),direction_type,grad_x,grad_y);
        ScriptGroup.Future grad_direction = grad_direction_closure.getReturn();
        //grad_mag+grad_direction->non_max_suppressed
        ScriptGroup.Binding gradient_global_binding = new ScriptGroup.Binding(frameProc.getFieldID_gradient(),grad_mag);
        ScriptGroup.Closure non_max_suppressed_closure = detector_builder.addKernel(frameProc.getKernelID_non_max_suppression(),flat_type,grad_mag,grad_direction,gradient_global_binding);
        ScriptGroup.Future non_max_suppressed = non_max_suppressed_closure.getReturn();
        //non_max_suppressed->hysteresis_thresholding in 2 parts
        ScriptGroup.Closure hthresh_closure_1 = detector_builder.addKernel(frameProc.getKernelID_hthreshold_part1(),flat_type,non_max_suppressed);
        ScriptGroup.Future hysteresis_threshold_1 = hthresh_closure_1.getReturn();
        //
        ScriptGroup.Binding hthresh_1_binding = new ScriptGroup.Binding(frameProc.getFieldID_hthresh1(),hysteresis_threshold_1);
        ScriptGroup.Closure hthresh_closure_2 = detector_builder.addKernel(frameProc.getKernelID_hthreshold_part2(),flat_type,hysteresis_threshold_1,hthresh_1_binding);
        ScriptGroup.Future hysteresis_threshold = hthresh_closure_2.getReturn();

        ScriptGroup.Binding hthresh_2_binding = new ScriptGroup.Binding(frameProc.getFieldID_hthresh1(),hysteresis_threshold);
        ScriptGroup.Closure hthresh_closure_3 = detector_builder.addKernel(frameProc.getKernelID_hthreshold_part2(),flat_type,hysteresis_threshold,hthresh_2_binding);
        ScriptGroup.Future hysteresis_threshold_3 = hthresh_closure_3.getReturn();

        ScriptGroup.Binding hthresh_3_binding = new ScriptGroup.Binding(frameProc.getFieldID_hthresh1(),hysteresis_threshold_3);
        ScriptGroup.Closure hthresh_closure_4 = detector_builder.addKernel(frameProc.getKernelID_hthreshold_part2(),flat_type,hysteresis_threshold_3,hthresh_3_binding);
        ScriptGroup.Future hysteresis_threshold_4 = hthresh_closure_4.getReturn();

        ScriptGroup.Closure flat_to_rgba_closure = detector_builder.addKernel(frameProc.getKernelID_flat_to_rgba(),rgba_output_type,hysteresis_threshold_4 );
        ScriptGroup.Future rgba = flat_to_rgba_closure.getReturn();
        canny_edge_detector = detector_builder.create("CannyEdgeDetector",rgba);
        //canny edge detector needs to go from rgba to greyscale
    }

    /**
     * A surface to send rgba data out to
     * @param surface
     */
    public void setDisplayOutput(Surface surface){
        Message msg = new Message();
        msg.what = DISPLAY_SURFACE;
        msg.obj = surface;
        mInitHandler.sendMessage(msg);
    }

    /**
     * set height and width of allocations
     * @param outputSize
     */
    public void setDimensions(Size outputSize) {
        width = outputSize.getWidth();
        height = outputSize.getHeight();
        initLock.release();
    }


    /**
     * Release all memory and objects
     */
    public void destroy(){
        display_output.destroy();
        RenderScript.releaseAllContexts();

        mInitHandler.removeCallbacksAndMessages(null);
        mInitHandler = null;

        mFrameIOHandler.removeCallbacksAndMessages(null);
        mFrameIOHandler = null;

        mHandlerThread.quit();
        mHandlerThread = null;
    }

    public void setGradientFilter(int filterOption) {
        //might have timing issue here
        //should save all these to preference file
        //and then read from preference file
        //fuuuuck
        //should also be able to change filter on demand
        //but not while processing
        switch(filterOption){
            case PREWITT_FILTER_OPTION:
                filter_dx = PREWITT_FILTER_X;
                filter_dy = PREWITT_FILTER_Y;
                break;
            case SOBEL_FILTER_OPTION:
                 filter_dx = SOBEL_FILTER_X;
                 filter_dy = SOBEL_FILTER_Y;
                 break;
            case ROBERT_FILTER_OPTION:
                 filter_dx = ROBERTS_FILTER_X;
                 filter_dy = ROBERTS_FILTER_Y;
                 break;
            default:
        }
        convolve3x3_dx.setCoefficients(filter_dx);
        convolve3x3_dy.setCoefficients(filter_dy);
    }

    public void setBlurRadius(float blurRadius) {
        intrinsicBlur.setRadius(blurRadius);
    }

    public void setThreshholdBounds(int max,int min){
        frameProc.set_h_max(max);
        frameProc.set_h_min(min);
    }
    //needs to be a thread that runs as long as the process runs
    //might need to re-initialize everything on restart
    private class InitHandler extends Handler {
        public InitHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if(msg.what == INITIALIZE_ALLOCS){
                //wait until dimensions have been calculated and set
                System.out.println("Waiting to initialize CameraFrameProcessor...");
                try {
                    initLock.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                initTypes();
                initAllocations();
                initScripts();
                System.out.println("Initialized CameraFrameProcessor.");

            }else if(msg.what == DISPLAY_SURFACE){
                display_output.setSurface((Surface)msg.obj);
            }else {
                super.handleMessage(msg);
            }
        }
    }

    private class FrameIOHandler extends Handler{
        public FrameIOHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            //only camera messages
            if(msg.what == NEW_CAMERA_FRAME){
                camera_input.ioReceive();//accept the frame from the camera
                //convert to rgba
                intrinsicYuvToRGB.forEach(display_output);
                display_output.ioSend();
            }else if(msg.what == EDGE_DETECT){
                System.out.println("EDGE_DETECTION_STARTED!");
                edge_detect_input.ioReceive();//accept the frame from the camera
                //convert to rgba
                Object[] results = canny_edge_detector.execute(0);
                Allocation result = (Allocation)results[0];
                display_output.copyFrom(result);
                display_output.ioSend();
            }else{
                super.handleMessage(msg);
            }
        }
    }

}
