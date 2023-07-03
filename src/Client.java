import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Scanner;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicInteger;

class FileReader extends Thread {
	private PacketBoundedBufferMonitor bufferMonitor;
	private String fileName;
	public FileReader() {}
	public FileReader(PacketBoundedBufferMonitor bm, String fileName) {
		this.bufferMonitor=bm;
		this.fileName=fileName;
	}

	public void run() {
		try {
			File file = new File(fileName);
			FileInputStream in = new FileInputStream(file);
			int packetIndex = 0;
			int readSize = 0;

			System.out.println(">> Begin to read a file" + Constants.CRLF);

			// Read the file header
			packetIndex = readFileHeader(packetIndex);

			// Read the file content
			byte[] buf = new byte[Constants.MAX_DATAGRAM_SIZE - 4];
			readSize = readFileContent(in, buf, packetIndex);

			if (readSize == -1) {
				Packet pkt = new Packet(-1, "End of reading a file".getBytes(), 0);
				this.bufferMonitor.deposit(pkt);
				System.out.println(">> Finish reading the file: " + fileName + Constants.CRLF);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private int readFileHeader(int packetIndex) {
		String fileHead = "fileName:" + fileName;
		Packet pkt = new Packet(packetIndex, fileHead.getBytes(), fileHead.getBytes().length);
		System.out.println(Constants.CRLF + ">> Prepare data for the head packet with index: " + pkt.getIndex());
		this.bufferMonitor.deposit(pkt);
		return packetIndex + 1;
	}

	private int readFileContent(FileInputStream in, byte[] buf, int packetIndex) throws IOException, IOException {
		int readSize;
		while (true) {
			readSize = in.read(buf, 0, buf.length);
			if (readSize == -1) {
				return -1;
			} else {
				Packet pkt = new Packet(packetIndex, buf, readSize);
				System.out.println(">> Read from a file for the packet with index " + pkt.getIndex());
				System.out.println(new String(buf, 0, readSize));
				this.bufferMonitor.deposit(pkt);
				packetIndex++;
			}
		}
	}
}

class PacketSender extends Thread {
	private static final AtomicInteger activeThreads = new AtomicInteger(0);
	private String threadName="PacketSender";
	private PacketBoundedBufferMonitor bufferMonitor;
	private DatagramSocket udpSenderSocket;
	private DatagramPacket udpSenderPacket;
	private InetAddress senderIp;//local
	private int senderPort;//local
	private InetAddress receiverIp;//remote
	private int receiverPort;//remote

	public PacketSender() {}
	public PacketSender(PacketBoundedBufferMonitor bm, InetAddress senderIp,int senderPort, InetAddress receiverIp,int receiverPort) {
		this.bufferMonitor=bm;
		this.senderIp=senderIp;
		this.senderPort=senderPort;
		this.receiverIp=receiverIp;
		this.receiverPort=receiverPort;
	}

	public void run() {
		activeThreads.incrementAndGet();
		byte[] buf=new byte[Constants.MAX_DATAGRAM_SIZE];
		byte []receiveBuf=new byte[1024];
		try {
			udpSenderSocket=new DatagramSocket(senderPort,senderIp);

			udpSenderPacket=new DatagramPacket(buf,Constants.MAX_DATAGRAM_SIZE,receiverIp,receiverPort);

			System.out.println(">> Begin to send packets"+Constants.CRLF);
			while(true) {
				//withdraw an item
				Packet pktS=this.bufferMonitor.withdraw();
				buf=new byte[Constants.MAX_DATAGRAM_SIZE];
				buf=pktS.packetToByteArray();
				udpSenderPacket.setData(buf,0,pktS.getContentSize()+4);
				udpSenderSocket.send(udpSenderPacket);

				System.out.println(">> Send the packet with index "+pktS.getIndex());

				// get an ACK packet
				while(true){
					try {
						udpSenderSocket.setSoTimeout(100);
						udpSenderPacket.setData(receiveBuf,0,receiveBuf.length);
						udpSenderSocket.receive(udpSenderPacket);
					}catch(SocketTimeoutException e) {
						// resend the packet if the corresponding ACK packet is not received
						System.out.println(">> Resend the packet with index "+pktS.getIndex()+Constants.CRLF);
						udpSenderPacket.setData(buf,0,pktS.getContentSize()+4);
						udpSenderSocket.send(udpSenderPacket);
					}
					int index=Helper.byteArrayToInt(Helper.get4Bytes(udpSenderPacket.getData()));
					if (index==pktS.getIndex()) {
						System.out.println("ACK for the packet with index "+index+Constants.CRLF);
						break;
					}
				}

				if (pktS.getIndex()==-1) {
					System.out.println(">> Finish sending packets.");
					break;
				}


				//sleep(10);

			}//end of while

		}catch(Exception e) {e.printStackTrace();}
		finally {
			if (activeThreads.decrementAndGet() == 0 && udpSenderSocket != null) {
				udpSenderSocket.close();
			}
		}
	}

	private void sleep(int i) {
		try {
			Thread.currentThread().sleep(100);
		}catch(Exception e) {e.printStackTrace();}

	}

}

class Constants {

	public static final int CLIENT_UDP_PORT = 15550;

	public static final int SERVER_UDP_PORT = 16667;
	public static final int SERVER_TCP_PORT = 16657;

	public static final int MAX_DATAGRAM_SIZE = 65500;

	public static final int MONITOR_BUFFER_SIZE=6;

	public static final String CRLF = "" + "\r\n";

}


public  class Client {
	private String fileName;
	private String directory;
//	private InetAddress serverIp;

	public Client(String fileName, String directory) {
		this.fileName = fileName;
		this.directory = directory;
	}

	public void sendFile() {

		String fullFileName=directory+File.separator+fileName;
		try {
			File file=new File(fullFileName);
			if (!file.exists()){
				System.out.println("File does not exist");
				return;
			}
			InetAddress serverIp=InetAddress.getByName("localhost");			
			Socket tcpSocket = new Socket(serverIp, Constants.SERVER_TCP_PORT);
			
			// get the port number from the server that will receive data through UDP datagrams
			String action="SEND REQUEST";
		    int serverPort=getPortFromServer(tcpSocket,action,fileName,Constants.CLIENT_UDP_PORT);
			if (serverPort==0) {return;}			
			
			
			// start sending the file
			PacketBoundedBufferMonitor bufferMonitor=new PacketBoundedBufferMonitor(Constants.MONITOR_BUFFER_SIZE);			
			InetAddress senderIp=InetAddress.getByName("localhost");			
			
			PacketSender packetSender=new PacketSender(bufferMonitor,senderIp,Constants.CLIENT_UDP_PORT,serverIp,serverPort);
			packetSender.start();
			
			FileReader fileReader=new FileReader(bufferMonitor,fullFileName);
			fileReader.start();
			
			try {
				packetSender.join();
				fileReader.join();				
			} 
	 		catch (InterruptedException e) {}
			
		}catch(Exception e) {e.printStackTrace();}
		
	}
	public void deleteFile() {
		try {
			InetAddress serverIp = InetAddress.getByName("localhost");
			Socket tcpSocket = new Socket(serverIp, Constants.SERVER_TCP_PORT);
			PrintWriter outputSocket = new PrintWriter(tcpSocket.getOutputStream(), true);
			String request = "DELETE REQUEST " + fileName;
			outputSocket.println(request + Constants.CRLF + "STOP");
			System.out.println(Constants.CRLF + ">> Request: " + request);

			tcpSocket.close();

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static int getPortFromServer(Socket tcpSocket,String action,String fileName,int udpPort) {
		int serverPort=0;
		try {
			Scanner inputSocket =  new Scanner(tcpSocket.getInputStream());
			PrintWriter outputSocket = new PrintWriter(tcpSocket.getOutputStream(), true);
			
			// send the HTTP packet	
			String request=action+" # "+fileName+" # "+udpPort;
		    outputSocket.println(request+Constants.CRLF+"STOP");
			System.out.println(Constants.CRLF+">> Request:"+request);
		    
			// receive the response	
		    String line=inputSocket.nextLine();
		    
		  // get the port number of the server that will receive data for the file		    
		    while(!line.equals("STOP")) {
		    	if (line.isEmpty()) {line=inputSocket.nextLine();continue;}
		    	if(line.startsWith(action)){
					// get the new port that is assigned by the server to receive data
		    		System.out.println(">> Response:"+line+Constants.CRLF);
					String [] items=line.split(":");					
					serverPort=Integer.parseInt(items[items.length-1]);
					break;
				}
		    	line=inputSocket.nextLine();
		    }
			 inputSocket.close();
		     outputSocket.close();
		}catch(Exception e) {e.printStackTrace();}
		return serverPort;
	}

}
