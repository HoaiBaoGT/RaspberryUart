package com.example.df_raspberry;

import android.app.Activity;


import android.os.Bundle;
import android.util.Log;

import com.google.android.things.pio.PeripheralManager;
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;

import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Example activity that provides a UART loopback on the
 * specified device. All data received at the specified
 * baud rate will be transferred back out the same UART.
 */
public class MainActivity extends Activity {
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

    //Read command----------------------------------------
    private static long[] Store_Message = new long[32];
    int Store_Size = 0;
    private static final int CRC_BASE = 13; // 13
    private static final char SendData_Start = '@';
    private static final char SendData_End = ',';

    private static long ReceiveData_Data;
    private static byte ReceiveData_Location;
    private static boolean ReceiveData_Ready = false;

    //Định nghĩa lệnh. Khoảng giá trị từ -128 đến 127. Tuy nhiên hiện tại chỉ nên dùng từ 0 đến 127. Lỗi chưa xác định.----------------------------------------
    private static final byte NhietDo = 0;
    private static final byte DoAm = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mService = PeripheralManager.getInstance();
        List<String> deviceList = mService.getUartDeviceList();
        if (deviceList.isEmpty()) {
            Log.i(TAG, "No UART port available on this device.");
        } else {
            Log.i(TAG, "List of available devices: " + deviceList);
            // Mo thiet bi uart
            try {
                openUart(UART_DEVICE_NAME, BAUD_RATE1);
                if(uartDevice != null) {
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
                    writeUartData(uartDevice, (byte)10, 500);
                    Message_Main();
                } catch (IOException e) {
                    e.printStackTrace();
                }
/*
                if(LED_FAG) {
                    try {
                        writeUartData(uartDevice, NhietDo, 8765);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                else {
                    try {
                        writeUartData(uartDevice, DoAm, 9876);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                LED_FAG = !LED_FAG;

                 */
            }
        };
        aTimer.schedule(aTask, 0, DURATION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

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
            char Data = (char)buffer[0];
            if(Data == '\n'){
                return;
            }
            if(Data == SendData_Start){
                ReceiveData_Ready = true;
                ReceiveData_Data = 0;
                ReceiveData_Location = 7;
                return;
            }
            if(Data == SendData_End){
                ReceiveData_Ready = false;
                if((ReceiveData_Data % CRC_BASE) == 0){
                    Store_Message[Store_Size] = ReceiveData_Data;
                    Store_Size++;
                }
                return;
            }
            if(ReceiveData_Ready && ReceiveData_Location > -1){
                ReceiveData_Data = ReceiveData_Data | (Long.parseLong(Character.toString(Data), 16) << (ReceiveData_Location*4));
                ReceiveData_Location--;
                return;
            }
        }
    }
    //Chuyển đổi nội dung lệnh Intel-Data sang định dạng gói tin "@IntelData," rồi gửi lên đường truyền==================================================
    public void writeUartData(UartDevice uart, byte Intel, int Data) throws IOException {
        long SendData_reg;
        SendData_reg = (long)1 << 31;
        SendData_reg = SendData_reg | ((long)Intel << 24);
        SendData_reg = SendData_reg | ((long)Data << 8);
        long CRC_odd = SendData_reg % CRC_BASE;
        CRC_odd = CRC_BASE - CRC_odd;
        SendData_reg = SendData_reg | CRC_odd;

        byte[] buffer = (SendData_Start + Long.toHexString(SendData_reg) + SendData_End).getBytes();
        int count = uart.write(buffer, buffer.length);
        uart.flush(UartDevice.FLUSH_OUT);
        Log.d(TAG, "Message: " + new String(buffer) + " length = " + count + " bytes");
    }
    //Đọc và xử lý lệnh đang có trong Store_Message==================================================
    public void Message_Main() throws IOException {
        if(Store_Size > 0){
            long Readed_Message = Store_Message[Store_Size -1];
            Store_Size--;
            byte Readed_Command = (byte)((Readed_Message >>> 24) & 0x7F);
            int Readed_Value = (int)((Readed_Message >>> 8) & 0xFFFF);

            Log.d(TAG, "Message | Command | Value : " + Readed_Message + " | " + Readed_Command + " | " + Readed_Value);
        }
    }
}