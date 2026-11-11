package io.github.fantuani.padpaste;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A short LAN discovery exchange; the Mac proves it knows this pairing password. */
final class MacDiscovery {
    static String discover(Config cfg) throws Exception {
        byte[] random=new byte[16];new SecureRandom().nextBytes(random);
        StringBuilder nonce=new StringBuilder();for(byte b:random)nonce.append(String.format(Locale.ROOT,"%02x",b&255));
        byte[] request=("PADPASTE_DISCOVER "+nonce).getBytes(StandardCharsets.UTF_8);
        Set<InetAddress> destinations=new HashSet<>();destinations.add(InetAddress.getByName("255.255.255.255"));
        for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())){
            if(!network.isUp()||network.isLoopback())continue;
            for(InterfaceAddress address:network.getInterfaceAddresses())if(address.getBroadcast()!=null)destinations.add(address.getBroadcast());
        }
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setBroadcast(true);long end=System.nanoTime()+900_000_000L;
            for(InetAddress address:destinations)try{socket.send(new DatagramPacket(request,request.length,address,5035));}catch(Exception ignored){}
            while(System.nanoTime()<end){
                socket.setSoTimeout(Math.max(1,(int)((end-System.nanoTime())/1_000_000L)));
                DatagramPacket packet=new DatagramPacket(new byte[512],512);
                try{socket.receive(packet);}catch(SocketTimeoutException e){break;}
                String[] reply=new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8).split(" ");
                if(reply.length!=4||!reply[0].equals("PADPASTE_FOUND")||!reply[1].equals(nonce.toString()))continue;
                int port;try{port=Integer.parseInt(reply[2]);}catch(NumberFormatException e){continue;}
                if(port<1||port>65535||!(packet.getAddress() instanceof Inet4Address))continue;
                Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(cfg.password.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                byte[] proof=hmac.doFinal(("padpaste|"+nonce+"|"+port).getBytes(StandardCharsets.UTF_8));
                StringBuilder expected=new StringBuilder();for(byte b:proof)expected.append(String.format(Locale.ROOT,"%02x",b&255));
                if(!MessageDigest.isEqual(expected.toString().getBytes(StandardCharsets.US_ASCII),reply[3].getBytes(StandardCharsets.US_ASCII)))continue;
                return "http://"+packet.getAddress().getHostAddress()+":"+port;
            }
        }
        throw new java.io.IOException("局域网内未找到已配对的 Mac");
    }
}
