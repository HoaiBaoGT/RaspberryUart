package com.example.df_raspberry;

import android.app.Activity;


import android.os.Bundle;
import android.util.Log;

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.google.android.things.pio.PeripheralManager;
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;

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
            mSocket = IO.socket("http://192.168.137.128:3000");
            Log.d("test somthing", "test here");

        } catch (URISyntaxException e) {
            Log.d("test somthing", "can not connect");
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

    private static final int DURATION = 1000;
    private static final int CHUNK_SIZE = 1;

    private PeripheralManager mService;
    private UartDevice uartDevice;

    //----------------------------------------
    //==================================================
    //Arduino command list
    //--------------------------------------------------
    private static final byte AR_NhietDo = 10;
    private static final byte AR_DoAm = 11;
    private static final byte AR_Quat = 20;
    private static final byte AR_ACS_Quat = 30;

    //Arduino value list
    //--------------------------------------------------
    private static int AR_Value_NhietDo = 0;
    private static int AR_Value_DoAm = 0;
    //==================================================

    //Read command Arduino----------------------------------------
    private static long[] Store_Message = new long[32];
    int Store_Size = 0;
    private static final int CRC_BASE = 13; // 13
    private static final char SendData_Start = '@';
    private static final char SendData_End = ',';

    private static long ReceiveData_Data;
    private static byte ReceiveData_Location;
    private static boolean ReceiveData_Ready = false;

    //Send command Socket--------------------------------------------------
    private static String[] Socket_send_command = new String[32];
    private static int[] Socket_send_value = new int[32];
    private static int Socket_send_size = 0;
    //Read command Socket--------------------------------------------------
    private static int[] Socket_read_command = new int[32];
    private static int[] Socket_read_value = new int[32];
    private static int Socket_read_size = 0;
    //Socket Listener============================================================
    private Emitter.Listener S_Quat = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            JSONObject object = (JSONObject) args[0];
            try {

//                String data = object.getString("noidung");
                JSONObject data = object.getJSONObject("noidung");
                String enable = data.getString("status");
//                String enable = object.getString("enable");
                Log.d("data receive led", enable);
                //--------------------------------------------------
                Socket_read_command[Socket_read_size] = SR_Quat_Command;
                Socket_read_value[Socket_read_size] = Integer.parseInt(enable);
                Socket_read_size++;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };
    private Emitter.Listener S_getsensor = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            JSONObject object = (JSONObject) args[0];
            try {
//                String data = object.getString("noidung");
                JSONObject data = object.getJSONObject("noidung");
                String enable = data.getString("status");
//                String enable = object.getString("enable");
                Log.d("data receive sensor", enable);
                //--------------------------------------------------
                Socket_read_command[Socket_read_size] = SR_Sensor_Command;
                Socket_read_value[Socket_read_size] = Integer.parseInt(enable);
                Socket_read_size++;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };
    //==================================================
    //Socket command list
    private static final byte SR_Sensor_Command = 10;
    private static final byte SR_Quat_Command = 20;
    private static final byte SR_ACS_Quat = 30;


    //--------------------------------------------------
    //Socket value list
    private static final String AS_Value = "";
    //==================================================


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSocket.connect();
        mSocket.on("server_send_data", S_Quat);
        mSocket.on("server_send_getsensor", S_getsensor);
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

    //Vòng lặp mỗi 1s==================================================
    private void setupTask() {
        Timer aTimer = new Timer();
        TimerTask aTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    Message_Main();
                    ReadMessage_Server();
                    SendMessage_Server();
                } catch (IOException e) {
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
                readUartBuffer(uart);
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
        // Configure the UART
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
    public void readUartBuffer(UartDevice uart) throws IOException {
        // Maximum amount of data to read at one time
        byte[] buffer = new byte[CHUNK_SIZE];
        while ((uart.read(buffer, buffer.length)) > 0) {
            char Data = (char) buffer[0];
            if (Data == '\n') {
                return;
            }
            if (Data == SendData_Start) {
                ReceiveData_Ready = true;
                ReceiveData_Data = 0;
                ReceiveData_Location = 7;
                return;
            }
            if (Data == SendData_End) {
                ReceiveData_Ready = false;
                if ((ReceiveData_Data % CRC_BASE) == 0) {
                    Store_Message[Store_Size] = ReceiveData_Data;
                    Store_Size++;
                }
                return;
            }
            if (ReceiveData_Ready && ReceiveData_Location > -1) {
                ReceiveData_Data = ReceiveData_Data | (Long.parseLong(Character.toString(Data), 16) << (ReceiveData_Location * 4));
                ReceiveData_Location--;
                return;
            }
        }
    }

    //Chuyển đổi nội dung lệnh Intel-Data sang định dạng gói tin "@IntelData," rồi gửi lên đường truyền==================================================
    public void writeUartData(UartDevice uart, byte Intel, int Data) throws IOException {
        long SendData_reg;
        SendData_reg = (long) 1 << 31;
        SendData_reg = SendData_reg | ((long) Intel << 24);
        SendData_reg = SendData_reg | ((long) Data << 8);
        long CRC_odd = SendData_reg % CRC_BASE;
        CRC_odd = CRC_BASE - CRC_odd;
        SendData_reg = SendData_reg | CRC_odd;

        byte[] buffer = (SendData_Start + Long.toHexString(SendData_reg) + SendData_End).getBytes();
        int count = uart.write(buffer, buffer.length);
        uart.flush(UartDevice.FLUSH_OUT);
        Log.d(TAG, "Message: " + new String(buffer));
    }

    //Đọc và xử lý lệnh đang có trong Store_Message từ Arduino==================================================
    public void Message_Main() throws IOException {
        while (Store_Size > 0) {
            long Readed_Message = Store_Message[Store_Size - 1];
            Store_Size--;
            byte Readed_Command = (byte) ((Readed_Message >>> 24) & 0x7F);
            int Readed_Value = (int) ((Readed_Message >>> 8) & 0xFFFF);
            Log.d(TAG, "Message | Command | Value : " + Readed_Message + " | " + Readed_Command + " | " + Readed_Value);

            switch (Readed_Command) {
                case AR_NhietDo:
                    AR_Value_NhietDo = Readed_Value;
                    Log.d(TAG, "AR_Value_NhietDo has been updated. Value = " + AR_Value_NhietDo);
                    break;
                case AR_DoAm:
                    AR_Value_DoAm = Readed_Value;
                    Log.d(TAG, "AR_Value_DoAm has been updated. Value = " + AR_Value_DoAm);
                    break;
                case AR_Quat:
                    AR_Value_DoAm = Readed_Value;
                    Socket_send_command[Socket_send_size] = "Relay";
                    Socket_send_value[Socket_send_size] = Readed_Value;
                    Socket_send_size++;
                    Log.d(TAG, "Relay = " + Readed_Value);
                    break;
                case AR_ACS_Quat:
                    Log.d(TAG, "AR_ACS_Quat = " + Readed_Value);
                    break;
                default:
                    Log.d(TAG, "Undefined command.");
                    break;
            }
        }
    }

    //Gửi dữ liệu lên Server Socket==================================================
    public void SendMessage_Server() {
        JSONObject my_obj = new JSONObject();
        boolean my_obj_check = false;
        while (Socket_send_size > 0) {
            Socket_send_size--;
            try {
                my_obj.put(Socket_send_command[Socket_send_size], Socket_send_value[Socket_send_size]);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            Log.d(TAG, "Message to Server: put");
            my_obj_check = true;
        }
        if (my_obj_check) {
            mSocket.emit("rasp_send_data", my_obj);
            Log.d(TAG, "Message to Server: done");//Không biết viết log thế nào luôn.
        }
    }

    //Đọc và xử lý lệnh từ Server==================================================
    public void ReadMessage_Server() throws IOException {
        while (Socket_read_size > 0) {
            Socket_read_size--;
            switch (Socket_read_command[Socket_read_size]) {
                case SR_Quat_Command:
                    writeUartData(uartDevice, AR_Quat, Socket_read_value[Socket_read_size]);
                    break;
                case SR_Sensor_Command:
                    Socket_send_command[Socket_send_size] = "NhietDo";
                    Socket_send_value[Socket_send_size] = AR_Value_NhietDo;
                    Socket_send_size++;
                    Socket_send_command[Socket_send_size] = "DoAm";
                    Socket_send_value[Socket_send_size] = AR_Value_DoAm;
                    Socket_send_size++;
                    break;
                default:
                    break;
            }
        }
    }
    //==================================================
    //Đảm bảo gói tin truyền nhận được.
    //==================================================
}