package com.example.df_raspberry;

import android.app.Activity;


import android.os.Bundle;
import android.util.Log;

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.google.android.things.pio.PeripheralManager;
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Example activity that provides a UART loopback on the
 * specified device. All data received at the specified
 * baud rate will be transferred back out the same UART.
 */
public class MainActivity extends Activity {
    // Socket IO connect to server
    private com.github.nkzawa.socketio.client.Socket mSocket;

    {
        try {
            mSocket = IO.socket("http://192.168.2.18:5000");
            String ten_cua_may = "abc123";
            mSocket.emit("rasp-ready", ten_cua_may);
            Log.d(TAG, "Socket connected.");

        } catch (URISyntaxException e) {
            Log.d(TAG, "Socket can not connect.");
        }
    }

    // UART Configuration Parameters
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String UART_DEVICE_NAME = "UART0";
    // UART Configuration Parameters
    private static final int BAUD_RATE1 = 9600;
    private static final int BAUD_RATE2 = 115200;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;

    private static final int DURATION = 100;
    private static final int CHUNK_SIZE = 1;

    private PeripheralManager mService;
    private UartDevice uartDevice;

    //==================================================
    private static String Raspberry_Name = "rasp_send_data";

    //==================================================
    private static class general_message {
        String i;
        String v;
    }

    /*----------------------------------------
        UART
    ----------------------------------------*/
    private static final int SIZE_MESSAGE = 32;
    private static final int SIZE_CRC = 8;
    private static final long CRC_KEY = 0x1D5;
    private static final char Message_STR = '[';
    private static final char Message_MID = '|';
    private static final char Message_END = ']';
    private static long uin_i_value;
    private static long uin_v_value;
    private static byte uin_i_index;
    private static byte uin_v_index;
    private static boolean uin_is_i = false;
    private static boolean uin_is_v = false;

    private static int[] UM_i = new int[32];
    private static int[] UM_v = new int[32];
    private static int UM_size = 0;
    /*----------------------------------------
        Socket
    ----------------------------------------*/
    private static int[] SM_i = new int[32];
    private static int[] SM_v = new int[32];
    private static int SM_size = 0;
    //Socket Listener============================================================
    private Emitter.Listener Server_Listener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            //Log.d(TAG, "Server_Listener");
            try {
                JSONObject object = (JSONObject) args[0];
                JSONArray se = object.getJSONArray("data");
                Log.d("control received: ", se.toString());
                String code = object.getString("machine");
                Log.d("code received: ", code);

                for (int p = 0; p < se.length(); p++) {
                    String i = se.getJSONObject(p).getString("id");
                    String v = se.getJSONObject(p).getString("status");
                    SM_i[SM_size] = s_to_i(i);
                    SM_v[SM_size] = s_to_i(v);
                    SM_size++;
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSocket.connect();
        mSocket.on("server_send_control", Server_Listener);
        mService = PeripheralManager.getInstance();
        List<String> deviceList = mService.getUartDeviceList();
        if (deviceList.isEmpty()) {
            Log.i(TAG, "No UART port available on this device.");
        } else {
            Log.i(TAG, "List of available devices: " + deviceList);
            // Mo thiet bi uart
            try {
                openUart(UART_DEVICE_NAME, BAUD_RATE1);
                if (uartDevice != null) {
                    Log.d("OpenUartDevice", "Success.");
                    setupTask();
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to open UART device", e);
            }
        }
    }

    /*
    Vòng lặp mỗi 100ns
    */
    int test = 0;
    private void setupTask() {
        Timer aTimer = new Timer();
        TimerTask aTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    if (SM_size > 0) {
                        SM_size--;
                        int i = SM_i[SM_size];
                        int v = SM_v[SM_size];
                        writeUartData(uartDevice, i, v);
                    }

                    if (UM_size > 0) {
                        UM_size--;
                        String i = i_to_s(UM_i[UM_size]);
                        int v = UM_v[UM_size];
                        Log.d(TAG, i + "|" + v);

                        JSONObject obj = new JSONObject();
                        JSONObject objChild= new JSONObject();

                        objChild.put(i, v);
                        obj.put("machine","abc123");
                        obj.put("data",objChild);
                        mSocket.emit(Raspberry_Name, obj);
                        if (UM_size == 0){
                            JSONObject tmp = new JSONObject();
                            tmp.put("machine","abc123");
                            mSocket.emit("rasp_send_data_complete",tmp);
                        }
                    }
                    /*
                    test = (test + 1) % 4;
                    int x = s_to_i("R" + Integer.toString(test+1));
                    int y = s_to_i("T");
                    writeUartData(uartDevice, x, y);
                    */
                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        aTimer.schedule(aTask, 0, DURATION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mSocket.disconnect();
        // Attempt to close the UART device
        try {
            closeUart();
        } catch (IOException e) {
            Log.e(TAG, "Error closing UART device:", e);
        }
    }

    private UartDeviceCallback mUartCallback = new UartDeviceCallback() {
        @Override
        public boolean onUartDeviceDataAvailable(UartDevice uart) {
            // Read available data from the UART device
            try {
                readUartData(uart);
            } catch (IOException e) {
                Log.w(TAG, "Unable to access UART device", e);
            }

            // Continue listening for more interrupts
            return true;
        }

        @Override
        public void onUartDeviceError(UartDevice uart, int error) {
            Log.w(TAG, uart + ": Error event " + error);
        }
    };
    /* Private Helper Methods */

    /**
     * Access and configure the requested UART device for 8N1.
     *
     * @param name     Name of the UART peripheral device to open.
     * @param baudRate Data transfer rate. Should be a standard UART baud,
     *                 such as 9600, 19200, 38400, 57600, 115200, etc.
     * @throws IOException if an error occurs opening the UART port.
     */
    private void openUart(String name, int baudRate) throws IOException {
        uartDevice = mService.openUartDevice(name);

        uartDevice.setBaudrate(baudRate);
        uartDevice.setDataSize(DATA_BITS);
        uartDevice.setParity(UartDevice.PARITY_NONE);
        uartDevice.setStopBits(STOP_BITS);

        uartDevice.registerUartDeviceCallback(mUartCallback);
    }

    /**
     * Close the UART device connection, if it exists
     */
    private void closeUart() throws IOException {
        if (uartDevice != null) {
            uartDevice.unregisterUartDeviceCallback(mUartCallback);
            try {
                uartDevice.close();
            } finally {
                uartDevice = null;
            }
        }
    }

    //Đọc dữ liệu nhận được và giải mã ra lệnh và lưu vào Store_Message==================================================
    public void readUartData(UartDevice uart) throws IOException {
        // Maximum amount of data to read at one time
        byte[] buffer = new byte[CHUNK_SIZE];
        while ((uart.read(buffer, buffer.length)) > 0) {
            char pick = (char) buffer[0];
            if (pick == '\n') {
                continue;
            }
            if (pick == Message_STR) {
                uin_i_value = 0;
                uin_i_index = 5;
                uin_is_i = true;
                uin_is_v = false;
                continue;
            }
            if (pick == Message_MID) {
                uin_v_value = 0;
                uin_v_index = 5;
                uin_is_i = false;
                uin_is_v = true;
                continue;
            }
            if (pick == Message_END) {
                if (message_check(uin_i_value) && message_check(uin_v_value) && (UM_size < 31)) {
                    int i = (int)(uin_i_value >> SIZE_CRC);
                    int v = (int)(uin_v_value >> SIZE_CRC);
                    UM_i[UM_size] = i;
                    UM_v[UM_size] = v;
                    UM_size++;
                    Log.d(TAG, "UART <= Arduino: " + "[" + i_to_s(i) + "|" + v + "]");
                }
                uin_is_i = false;
                uin_is_v = false;
                continue;
            }
            if (uin_is_i && (uin_i_index >= 0)) {
                uin_i_value = uin_i_value | (Long.parseLong(Character.toString(pick), 16) << (uin_i_index * 4));
                uin_i_index--;
                continue;
            }
            if (uin_is_v && (uin_v_index >= 0)) {
                uin_v_value = uin_v_value | (Long.parseLong(Character.toString(pick), 16) << (uin_v_index * 4));
                uin_v_index--;
                continue;
            }
        }
    }

    public void writeUartData(UartDevice uart, int i, int v) throws IOException {
        long cache_i = message_generate(i);
        long cache_v = message_generate(v);
        String ST = "";
        ST = ST + Message_STR;
        for (int count = 5; count >= 0; count--) {
            int B = (int) ((cache_i >> (count * 4)) & 0xF);
            ST = ST + Integer.toHexString(B);
        }
        ST = ST + Message_MID;
        for (int count = 5; count >= 0; count--) {
            int B = (int) ((cache_v >> (count * 4)) & 0xF);
            ST = ST + Integer.toHexString(B);
        }
        ST = ST + Message_END;
        byte[] buffer = ST.getBytes();
        int count = uart.write(buffer, buffer.length);
        uart.flush(UartDevice.FLUSH_OUT);
        Log.d(TAG, "UART => Arduino: " + new String(buffer) + " " + count);
    }
    /*
    CRC
     */
    private long message_generate(int data) {
        long odd = data << SIZE_CRC;
        for (int i = (SIZE_MESSAGE - 1); i >= SIZE_CRC; i--) {
            long check = odd >> i;
            if (check != 0) {
                long divisor = CRC_KEY << (i - SIZE_CRC);
                odd = odd ^ divisor;
            }
        }
        return (data << SIZE_CRC) | odd;
    }

    private boolean message_check(long message) {
        if ((message >> SIZE_CRC) == 0) {
            return false;
        }
        long odd = message;
        for (int i = (SIZE_MESSAGE - 1); i >= SIZE_CRC; i--) {
            long check = odd >> i;
            if (check != 0) {
                long divisor = CRC_KEY << (i - SIZE_CRC);
                odd = odd ^ divisor;
            }
        }
        return odd == 0;
    }

    private String i_to_s(int i) {
        byte[] x = new byte[2];
        for (int p = 0; p < 2; p++){
            byte get = (byte)(0xFF & (i >> (p*8)));
            if (get !=0){
                x[p] = get;
            }
        }
        String s = new String(x);
        return s;
    }

    private int s_to_i(String s) {
        int i = 0;
        int l = s.length();
        byte[] x = s.getBytes();
        if (l > 0) {
            i = i | x[0];
        }
        if (l > 1) {
            i = i | (x[1] << 8);
        }
        return i;
    }
}