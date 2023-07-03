import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

class PacketReceiver extends Thread {
	private String threadName = "PacketReceiver";
	private PacketBoundedBufferMonitor bufferMonitor;
	private DatagramSocket udpReceiverSocket;
	private DatagramPacket udpReceiverPacket;
	private InetAddress receiverIp; // local
	private int receiverPort; // local
	private InetAddress senderIp; // remote
	private int senderPort; // remote

	public PacketReceiver() {
	}

	public PacketReceiver(PacketBoundedBufferMonitor bm, InetAddress receiverIp, int receiverPort,
						  InetAddress senderIp, int senderPort) {
		this.bufferMonitor = bm;
		this.receiverIp = receiverIp;
		this.receiverPort = receiverPort;
		this.senderIp = senderIp;
		this.senderPort = senderPort;

	}

	public void run() {
		try {
			byte[] buf = new byte[Constants.MAX_DATAGRAM_SIZE];
			udpReceiverSocket = new DatagramSocket(receiverPort, receiverIp);
			udpReceiverPacket = new DatagramPacket(buf, Constants.MAX_DATAGRAM_SIZE, senderIp, senderPort);
			int expectPacketIndex = 0;
			int currentPacketIndex = 0;
			boolean depositFlag = false;

			System.out.println(">> Begin to receive packets" + Constants.CRLF);
			while (true) {
				// receive packets
				buf = new byte[Constants.MAX_DATAGRAM_SIZE];
				udpReceiverPacket.setData(buf, 0, buf.length);
				udpReceiverSocket.receive(udpReceiverPacket);

				Packet pkt = new Packet(udpReceiverPacket.getData(), udpReceiverPacket.getLength());
				System.out.println(">> Receive the packet with index " + pkt.getIndex());

				// send an ACK packet in case that the packet has already received.
				currentPacketIndex = pkt.getIndex();
				if (currentPacketIndex != -1) {
					while (currentPacketIndex < expectPacketIndex) {
						// send an ACK with the packet index received
						udpReceiverPacket.setData(Helper.intToByteArray(currentPacketIndex), 0, 4);
						udpReceiverSocket.send(udpReceiverPacket);

						buf = new byte[Constants.MAX_DATAGRAM_SIZE];
						udpReceiverSocket.receive(udpReceiverPacket);
						pkt = new Packet(udpReceiverPacket.getData(), udpReceiverPacket.getLength());
						currentPacketIndex = pkt.getIndex();
					}
				}

				// send an ACK with the packet index received
				udpReceiverPacket.setData(Helper.intToByteArray(currentPacketIndex), 0, 4);
				udpReceiverSocket.send(udpReceiverPacket);
				System.out.println("   Send an ACK packet for packet " + currentPacketIndex + Constants.CRLF);

				// deposit
				this.bufferMonitor.deposit(pkt);
				// increase expectPacketIndex
				expectPacketIndex += 1;

				if (pkt.getIndex() == -1) {
					System.out.println(">> Finish receiving packets");
					break;
				}

			} // end of receiving a file

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (udpReceiverSocket != null) {
				udpReceiverSocket.close();
			}
		}
	}
}

class FileWriter extends Thread {
	private String threadName = "FileWriter";
	private PacketBoundedBufferMonitor bufferMonitor;
	FileChannel channel = null;
	String folderPath = "serverFileHolder";

	public FileWriter() {
	}

	public FileWriter(PacketBoundedBufferMonitor bm) {
		this.bufferMonitor = bm;
	}

	public void run() {
		try {
			String fileName = "";
			ByteBuffer buffer = ByteBuffer.allocate(Constants.MAX_DATAGRAM_SIZE);
			List<Integer> packetIndices = new ArrayList<>();

			System.out.println(">> Begin to write packets to a file" + Constants.CRLF);
			while (true) {

				Packet pkt = this.bufferMonitor.withdraw();

				if (pkt.getIndex() == -1) {
					System.out.println(">> Finish saving the file:" + fileName);
					System.out.println(">> Packet indices: " + packetIndices);
					break;
				}

				if (pkt.getIndex() == 0) {
					// read the head packet
					String msg = pkt.getContentInString();
					String fullPath = msg.split(":")[1]; // head packet content: "fileName: file name"
					Path filePath = Paths.get(fullPath);
					fileName = filePath.getFileName().toString();

					File file = new File(folderPath + File.separator + fileName);
					file.createNewFile();
					channel = new FileOutputStream(file, false).getChannel();
					System.out.println(Constants.CRLF + ">> Prepare to write the file " + fileName + Constants.CRLF);

				} else {
					// write packets to a file
					System.out.println(">> Write to a file the packet with index " + pkt.getIndex());
					buffer = ByteBuffer.wrap(pkt.getContent(), 0, pkt.getContentSize());
					channel.write(buffer);
					packetIndices.add(pkt.getIndex());
				}

			} // end of writing a file

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (channel != null) {
					channel.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}// end of run()

}

public class Server {

	public Server() {
	}

	public static void main(String[] args) {
		try {
			ServerSocket serverSocket = null;
			try {
				serverSocket = new ServerSocket(Constants.SERVER_TCP_PORT);
			} catch (IOException ioEx) {
				System.out.println("\n>> Unable to set up port!");
				System.exit(1);
			}

			System.out.println("\r\n>> Ready to accept requests");
			do {
				try {
					Socket client = serverSocket.accept();
					System.out.println("\n>> New request is accepted." + Constants.CRLF);

					Scanner inputSocket = new Scanner(client.getInputStream());
					PrintWriter outputSocket = new PrintWriter(client.getOutputStream(), true);

					String line = inputSocket.nextLine();
					String actionType = "";
					int clientUDPPort = 0;
					String fileName = "";
					while (!line.equals("STOP")) {
						if (line.isEmpty()) {
							line = inputSocket.nextLine();
							continue;
						}
						if (line.startsWith("SEND REQUEST")) {
							System.out.println(">> Request: " + line + Constants.CRLF);
							actionType = "SEND REQUEST";
							clientUDPPort = Integer.parseInt(line.split("#")[2].strip());
							break;
						}
						if (line.startsWith("DELETE")) {
							System.out.println(">> Delete request: " + line + Constants.CRLF);
							actionType = "DELETE";
							fileName = line.split(" ")[2].strip();
							System.out.println(fileName);
							break;
						}
						line = inputSocket.nextLine();
					}

					if (actionType.equals("SEND REQUEST")) {
						receiveHandle(client, outputSocket, clientUDPPort);
					}

					if (actionType.equals("DELETE")) {
						deleteFile(fileName);
					}

				} catch (IOException io) {
					System.out.println(">> Fail to listen to requests!");
					System.exit(1);
				}
			} while (true);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void receiveHandle(Socket socket, PrintWriter outputSocket, int senderPort) {
		try {
			String response = "SEND REQUEST OK: receive data with the port:" + Constants.SERVER_UDP_PORT;
			System.out.println(">> Response: " + response + Constants.CRLF);

			outputSocket.println(response + Constants.CRLF + "STOP");
			outputSocket.close();

			PacketBoundedBufferMonitor bm = new PacketBoundedBufferMonitor(Constants.MONITOR_BUFFER_SIZE);
			InetAddress senderIp = socket.getInetAddress();
			InetAddress receiverIp = InetAddress.getByName("localhost");

			receiveFile(bm, receiverIp, Constants.SERVER_UDP_PORT, senderIp, senderPort);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void receiveFile(PacketBoundedBufferMonitor bm, InetAddress receiverIp, int receiverPort,
								   InetAddress senderIp, int senderPort) {
		PacketReceiver packetReceiver = new PacketReceiver(bm, receiverIp, receiverPort, senderIp, senderPort);
		packetReceiver.start();

		FileWriter fileWriter = new FileWriter(bm);
		fileWriter.start();
		try {
			packetReceiver.join();
			fileWriter.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void deleteFile(String fileName) {
		try {
			String fullPath = "serverFileHolder" + File.separator + fileName;
			System.out.println(fullPath);
			File fileToDelete = new File(fullPath);
			if (fileToDelete.delete()) {
				System.out.println("File deleted successfully");
			} else {
				System.out.println("Failed to delete the file");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

