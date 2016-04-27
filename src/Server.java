import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class Server {
	private static final int BUFFER_SIZE = 4000;
	private static final int PORT = 6789;
	private static DatagramSocket serverSocket;
	private static int dropRate = 20;
	private static int clientsSequenceNumber;
	private static int serverSequenceNumber;
	private static InetAddress CLIENT_ADDR;
	private static int nBits = 5;
	private static int windowSize = 4;
	private static PrintWriter writer;
	private static String ServerLog = "D:/serverLog.txt";
	private static String folderPath = "D:/";
	
	public static void sendFile(int initialSequence, String inFile, PrintWriter pw, InetAddress IPAddress, int PORT,
		DatagramSocket socket, int dropRate) throws IOException {
		socket.setSoTimeout(500);
		ArrayList<byte[]> list = splitFile(inFile, initialSequence);
		Queue<Integer> unAckedPackets = new LinkedList<Integer>();

		int sequenceNum = 0;
		int ctr = 0;
		int initialSeq = initialSequence;
		// boolean timedOut = true;

		while (ctr != list.size() || unAckedPackets.size() != 0) {

			byte[] receiveData = new byte[BUFFER_SIZE];

			try {
				while (unAckedPackets.size() < windowSize && ctr < list.size()) {
					// retrieve data packet form list
					byte[] data = list.get(ctr);
					DatagramPacket packet = new DatagramPacket(data, data.length, IPAddress, PORT);
					socket.send(packet);

					byte[] sequenceNumberArray = new byte[4];
					sequenceNumberArray[0] = data[0];
					sequenceNumberArray[1] = data[1];
					sequenceNumberArray[2] = data[2];
					sequenceNumberArray[3] = data[3];

					sequenceNum = ByteBuffer.wrap(sequenceNumberArray).getInt();

					log("Sending Packet " + sequenceNum, pw);

					// add to window
					unAckedPackets.add(ctr);
					ctr++;
				}

				Random random = new Random();
				int chance = random.nextInt(100);

				// Receive ack
				for (int i = 0; i < unAckedPackets.size(); i++) {
					//System.out.println("**unack size: " +unAckedPackets.size());
					//System.out.println(list.size() + initialSeq-1);
					//System.out.println(unAckedPackets.peek() + initialSeq);
					DatagramPacket received = new DatagramPacket(receiveData, receiveData.length);
					socket.receive(received);
					int returnedSeq = getSequenceNumb(received.getData());
					if (chance > dropRate) {
						
						if (unAckedPackets.size() > 0) {

							if ((returnedSeq == (unAckedPackets.peek() + initialSeq))) {
								unAckedPackets.remove();
								log("ACK received:" + returnedSeq, pw);
							}

							else {
								log("ACK out-of-order: " + returnedSeq, pw);
							}
						}

					} else {
						if ((returnedSeq == (unAckedPackets.peek() + initialSeq)&&(returnedSeq == (list.size() + initialSeq-1)))) {
							unAckedPackets.remove();
							System.out.println("WTFFFFf");
							log("ACK received:" + returnedSeq, pw);
						}else{
							log("EMULATE ACK " + (sequenceNum + i) + " DROPPED", pw);
						}
						
					}
				}

			} catch (SocketTimeoutException exception) {
				// If we don't get an ack, prepare to resend sequence number
				log("TIMEOUT for packet: " + sequenceNum , pw);
				int ctr1 = 0;
				for (Integer i : unAckedPackets) {
					byte[] data = list.get(i);
					DatagramPacket packet = new DatagramPacket(data, data.length, IPAddress, PORT);
					socket.send(packet);

					log("RESENDING packet " + (sequenceNum + ctr1), pw);
					ctr1++;
				}

			}

		}
	}

	public static void log(String msg, PrintWriter pw) {
		System.out.println(msg);
		pw.println(msg);

	}

	private static int getSequenceNumb(byte[] chunk) {
		byte[] seq = new byte[4];
		seq[0] = chunk[0];
		seq[1] = chunk[1];
		seq[2] = chunk[2];
		seq[3] = chunk[3];

		return ByteBuffer.wrap(seq).getInt();

	}

	private static ArrayList<byte[]> splitFile(String path, int seqStart) throws IOException {
		ArrayList<byte[]> fileChunks = new ArrayList<byte[]>();
		File f = new File(path);
		BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f));
		int seqNumber = seqStart;
		int lastPacketLength = 0;
		while (true) {
			byte[] buffer = new byte[BUFFER_SIZE];
			byte[] sequenceNumberArray = ByteBuffer.allocate(4).putInt(seqNumber).array();
			buffer[0] = sequenceNumberArray[0];
			buffer[1] = sequenceNumberArray[1];
			buffer[2] = sequenceNumberArray[2];
			buffer[3] = sequenceNumberArray[3];

			int r = bis.read(buffer, 4, BUFFER_SIZE - 4);
			if (r == -1)
				break;
			fileChunks.add(buffer);
			lastPacketLength = r;
			seqNumber += 1;

		}

		String s = "      end of file," + lastPacketLength + "," + f.getName() + ",";
		byte[] sequenceNumberArray = ByteBuffer.allocate(4).putInt(seqNumber).array();
		byte[] lastPacket = s.getBytes("UTF-8");
		byte[] finalLastPacket = new byte[BUFFER_SIZE];

		finalLastPacket[0] = sequenceNumberArray[0];
		finalLastPacket[1] = sequenceNumberArray[1];
		finalLastPacket[2] = sequenceNumberArray[2];
		finalLastPacket[3] = sequenceNumberArray[3];

		System.arraycopy(lastPacket, 0, finalLastPacket, 4, lastPacket.length);

		fileChunks.add(finalLastPacket);
		bis.close();
		return fileChunks;
	}

	
	public static void clientReceiveFile(int initialSequence, String filePath, PrintWriter pw, InetAddress IPAddress,
			int PORT, DatagramSocket socket, int dropRate) throws IOException {
			socket.setSoTimeout(0);
			int lastReceivedPacket = -1;
			ArrayList<Integer> receivedList = new ArrayList<Integer>();
			int lastPacketSize = 0;
			

			// Set up byte arrays for sending/receiving data
			byte[] receiveData = new byte[BUFFER_SIZE];
			@SuppressWarnings("unused")
			byte[] dataForSend = new byte[BUFFER_SIZE];
			ArrayList<byte[]> fileChunks = new ArrayList<byte[]>();

			// Infinite loop to check for connections
			while (true) {

				// Get the received packet
				DatagramPacket received = new DatagramPacket(receiveData, receiveData.length);
				socket.receive(received);

				Random random = new Random();
				int chance = random.nextInt(100);

				byte[] data = new byte[BUFFER_SIZE];
				data = received.getData().clone();
				byte[] sequenceNumberArray = new byte[4];
				sequenceNumberArray[0] = data[0];
				sequenceNumberArray[1] = data[1];
				sequenceNumberArray[2] = data[2];
				sequenceNumberArray[3] = data[3];

				int sequenceNum = ByteBuffer.wrap(sequenceNumberArray).getInt();

				// 1 in 2 chance of responding to the message
				if (chance > dropRate) {
					if (lastReceivedPacket == -1 && sequenceNum == initialSequence) {
						fileChunks.add(data);
						receivedList.add(sequenceNum);
						lastReceivedPacket = sequenceNum;
						log("RECEIVED " + sequenceNum, pw);
						sendACK(received, sequenceNum, socket);
					}

					else if ((!receivedList.contains(sequenceNum)) && sequenceNum == (lastReceivedPacket + 1)) {
						fileChunks.add(data);
						receivedList.add(sequenceNum);
						lastReceivedPacket = sequenceNum;
						log("RECEIVED " + sequenceNum, pw);
						String rcvd = new String(received.getData(), 0, received.getLength());

						sendACK(received, sequenceNum, socket);

						if (rcvd.contains("end of file,")) {
							String[] s = rcvd.split(",");
							lastPacketSize = Integer.parseInt(s[1]);
							
							System.out.println(lastPacketSize);
							dataForSend = ByteBuffer.allocate(4).putInt(sequenceNum).array();

							// Send the packet data back to the client
							sendACK(received, sequenceNum, socket);

							break;
						}
					} else {
						log("RECEIVED OUT OF ORDER packet: " + sequenceNum + ", DROPPED", pw);
						sendACK(received, sequenceNum, socket);

					}

				} else {
					log("EMULATE PACKET " + sequenceNum + " DROPPED", pw);
				}
			}
			// System.out.println(fileChunks.size());
			//System.out.println("File chunk size: " + fileChunks.size());
			

			mergeFile(lastPacketSize, fileChunks, filePath);
			log("File Saved at: " + filePath, pw);
		}
	
	
	
	public static void receiveFile(int initialSequence, String folderPath, PrintWriter pw, InetAddress IPAddress,
		int PORT, DatagramSocket socket, int dropRate) throws IOException {
		socket.setSoTimeout(0);
		int lastReceivedPacket = -1;
		ArrayList<Integer> receivedList = new ArrayList<Integer>();
		int lastPacketSize = 0;
		String outFile = folderPath;

		// Set up byte arrays for sending/receiving data
		byte[] receiveData = new byte[BUFFER_SIZE];
		@SuppressWarnings("unused")
		byte[] dataForSend = new byte[BUFFER_SIZE];
		ArrayList<byte[]> fileChunks = new ArrayList<byte[]>();
		String name;
		// Infinite loop to check for connections
		while (true) {

			// Get the received packet
			DatagramPacket received = new DatagramPacket(receiveData, receiveData.length);
			socket.receive(received);

			Random random = new Random();
			int chance = random.nextInt(100);

			byte[] data = new byte[BUFFER_SIZE];
			data = received.getData().clone();
			byte[] sequenceNumberArray = new byte[4];
			sequenceNumberArray[0] = data[0];
			sequenceNumberArray[1] = data[1];
			sequenceNumberArray[2] = data[2];
			sequenceNumberArray[3] = data[3];

			int sequenceNum = ByteBuffer.wrap(sequenceNumberArray).getInt();

			// 1 in 2 chance of responding to the message
			if (chance > dropRate) {
				if (lastReceivedPacket == -1 && sequenceNum == initialSequence) {
					fileChunks.add(data);
					receivedList.add(sequenceNum);
					lastReceivedPacket = sequenceNum;
					log("RECEIVED " + sequenceNum, pw);
					sendACK(received, sequenceNum, socket);
				}

				else if ((!receivedList.contains(sequenceNum)) && sequenceNum == (lastReceivedPacket + 1)) {
					fileChunks.add(data);
					receivedList.add(sequenceNum);
					lastReceivedPacket = sequenceNum;
					log("RECEIVED " + sequenceNum, pw);
					String rcvd = new String(received.getData(), 0, received.getLength());

					sendACK(received, sequenceNum, socket);

					if (rcvd.contains("end of file,")) {
						String[] s = rcvd.split(",");
						lastPacketSize = Integer.parseInt(s[1]);
						name = s[2];
						//System.out.println(lastPacketSize);
						dataForSend = ByteBuffer.allocate(4).putInt(sequenceNum).array();

						// Send the packet data back to the client
						sendACK(received, sequenceNum, socket);

						break;
					}
				} else {
					log("RECEIVED OUT OF ORDER packet: " + sequenceNum + ", DROPPED", pw);
					sendACK(received, sequenceNum, socket);

				}

			} else {
				log("EMULATE PACKET " + sequenceNum + " DROPPED", pw);
			}
		}
		// System.out.println(fileChunks.size());
		System.out.println("File chunk size: " + fileChunks.size());
		
		File f = new File(outFile + name);
		String s = "File name exist.";
		String success = "success";
		String fileName = "";
		byte[] exist = s.getBytes("UTF-8");
		byte[] su = success.getBytes("UTF-8");
		byte[] rec = new byte[4000];
		String file1;
		
		while (f.exists() && !f.isDirectory()) {
			log("File exist.", pw);
			DatagramPacket packet = new DatagramPacket(exist, exist.length, IPAddress, PORT);
			socket.send(packet);
			do {
				DatagramPacket packetr = new DatagramPacket(rec, rec.length);
				socket.receive(packetr);
				
				file1 = new String(packetr.getData().clone(), 0, packetr.getLength());
				String[] files = file1.split(":");
				fileName = files[1];
			} while (!file1.contains("new file name:"));
			

			
			fileName = outFile + fileName.trim();
			f = new File(fileName);
			
		}
		DatagramPacket packet = new DatagramPacket(su, su.length, IPAddress, PORT);
		socket.send(packet);
		
		mergeFile(lastPacketSize, fileChunks, fileName);
		log("File Saved at: " + fileName, pw);
	}

	private static void sendACK(DatagramPacket received, int sequenceNum, DatagramSocket socket) throws IOException {
		byte[] dataForSend;
		dataForSend = ByteBuffer.allocate(4).putInt(sequenceNum).array();

		// send ack to client
		DatagramPacket packet = new DatagramPacket(dataForSend, dataForSend.length, received.getAddress(),
				received.getPort());
		socket.send(packet);
	}

	private static void mergeFile(int lps, ArrayList<byte[]> list, String path) throws IOException {
		
		int lastPacketSize = lps;
		File f = new File(path);
		FileOutputStream fos = new FileOutputStream(f);
		int offset = 0;
		int size = (BUFFER_SIZE - 4) * (list.size() - 2) + lastPacketSize;
		byte[] data = new byte[size];

		for (int i = 0; i < list.size() - 2; i++) {
			System.arraycopy(list.get(i), 4, data, offset, BUFFER_SIZE - 4);
			// fos.write(list.get(i), offset, BUFFER_SIZE);
			offset += (BUFFER_SIZE - 4);
			// log(offset);
		}
		System.arraycopy(list.get(list.size() - 2), 4, data, offset, lastPacketSize);

		fos.write(data, 0, size);
		fos.flush();
		fos.close();
		
	}

	public static ArrayList<String> receiveFileList(DatagramSocket socket) throws IOException {
		socket.setSoTimeout(0);
		ArrayList<String> fileList;
		byte[] receiveData = new byte[BUFFER_SIZE];

		DatagramPacket received = new DatagramPacket(receiveData, receiveData.length);
		socket.receive(received);
		String rcvd = new String(received.getData(), 0, received.getLength());
		System.out.println(rcvd);
		fileList = new ArrayList<String>(Arrays.asList(rcvd.split("[|]")));
		return fileList;
	}

	public static void sendFileNumber(DatagramSocket socket, InetAddress addr, int port, String number)
			throws IOException {

		byte[] dataToSend = number.getBytes("UTF-8");
		DatagramPacket packet = new DatagramPacket(dataToSend, dataToSend.length, addr, port);
		socket.send(packet);

	}

	public static String sendFileList(String folderPath, PrintWriter pw, InetAddress IPAddress, int PORT,
			DatagramSocket socket) throws IOException {
		int choice = 0;
		String path = "";
		String list = "";
		ArrayList<String> fileList = new ArrayList<String>();

		fileList = getContent(folderPath);
		for (String s : fileList) {
			list += s;
			list += "|";
		}

		byte[] data = list.getBytes("UTF-8");
		DatagramPacket packet = new DatagramPacket(data, data.length, IPAddress, PORT);
		socket.send(packet);

		while (true) {
			byte[] receiveData = new byte[BUFFER_SIZE];
			DatagramPacket received = new DatagramPacket(receiveData, receiveData.length);
			socket.receive(received);
			String rcvd = new String(received.getData(), 0, received.getLength());
			choice = Integer.parseInt(rcvd);
			path = folderPath + fileList.get(choice);
			break;
		}

		log("File list sent!", pw);
		return path;
	}

	public static ArrayList<String> getContent(String path) {
		ArrayList<String> list = new ArrayList<String>();
		File folder = new File(path);
		for (final File fileEntry : folder.listFiles()) {
			if (!fileEntry.isDirectory()) {
				list.add(fileEntry.getName());
			}
			
		}
		return list;

	}
	
	public static void main(String[] args) throws IOException {
		writer = new PrintWriter(ServerLog, "UTF-8");
		serverSequenceNumber = ThreadLocalRandom.current().nextInt(0, 255 + 1);
		log("Server: random number = " + serverSequenceNumber, writer);
		serverSequenceNumber = getSignificantNbits(nBits, serverSequenceNumber);
		log("Server: nbits = " + serverSequenceNumber, writer);

		// Create a server socket

		serverSocket = new DatagramSocket(PORT);
		

		

		while (true) {
			serverSocket.setSoTimeout(0);
			log("\n***SERVER READY TO ACCEPT CONNECTION***", writer);
			handShake(serverSocket);
			
			byte[] receiveData = new byte[BUFFER_SIZE];
			byte[] dataForSend = new byte[BUFFER_SIZE];
			String choice = "";
			int port = 5789;
			
			while (!choice.equals("1") && !choice.equals("2") && !choice.equals("3")) {
				log("Waiting for client request...", writer);
				DatagramPacket received = new DatagramPacket(receiveData, receiveData.length);
				serverSocket.receive(received);

				Random random = new Random();
				int chance = random.nextInt(100);
				if (chance > dropRate) {
					byte[] data = received.getData().clone();
					String str = new String(data, "UTF-8");
					String[] s = str.split(",");

					CLIENT_ADDR = received.getAddress();
					port = received.getPort();
					String reply = "ack flag";
					dataForSend = reply.getBytes("UTF-8");

					DatagramPacket packet = new DatagramPacket(dataForSend, dataForSend.length, CLIENT_ADDR, port);
					serverSocket.send(packet);

					dropRate = Integer.parseInt(s[0]);
					choice = s[1].trim();

				} else {
					log("Control packet dropped.", writer);
				}

			}
			
			if (choice.equals("1")) {
				log("Received request PUT", writer);
				receiveFile(clientsSequenceNumber, folderPath, writer, CLIENT_ADDR, port, serverSocket,
						dropRate);
			} else if (choice.equals("2")) {
				log("Received request GET", writer);
				String path = sendFileList(folderPath, writer, CLIENT_ADDR, port, serverSocket);
				sendFile(serverSequenceNumber, path, writer, CLIENT_ADDR, port, serverSocket, dropRate);

			} else if (choice.equals("3")) {
				log("Received request EXIT", writer);
				break;
			}
			byte[] buf = new byte[BUFFER_SIZE];
			DatagramPacket p = new DatagramPacket(buf, buf.length);
			serverSocket.setSoTimeout(50);
			for (int i = 0; i < 10; i++) {
				try {
					serverSocket.receive(p);
				} catch (Exception e) {
					System.out.println("flushing buffer");
				}
				
			}
			
			
			log("Operation complete.", writer);

		}

		log("Server exiting.", writer);
	
		writer.close();
	}

	private static void handShake(DatagramSocket serverSocket) {

		byte[] receiveBuffer = new byte[BUFFER_SIZE];
		byte[] sendBuffer = new byte[BUFFER_SIZE];
		sendBuffer = ByteBuffer.allocate(BUFFER_SIZE).putInt(serverSequenceNumber).array();
		DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, BUFFER_SIZE);
		DatagramPacket sendPacket = null;

		while (true) {

			try {
				serverSocket.receive(receivePacket);
				CLIENT_ADDR = receivePacket.getAddress();
				int port = receivePacket.getPort();
				sendPacket = new DatagramPacket(sendBuffer, BUFFER_SIZE, CLIENT_ADDR, port);
				ByteBuffer b = ByteBuffer.wrap(receivePacket.getData());
				clientsSequenceNumber = b.getInt();
				log("CONNECTION REQUEST from: " + CLIENT_ADDR.getHostAddress() + ":" + port, writer);
				log("Client sequence start: " + clientsSequenceNumber, writer);
				serverSocket.send(sendPacket);
				serverSocket.receive(receivePacket);
				b = ByteBuffer.wrap(receivePacket.getData());
				int i = b.getInt();
				log("Client ack: " + i, writer);

				if (i == serverSequenceNumber) {
					break;
				}

			} catch (IOException e) {

			}

		}

	}

	private static int getSignificantNbits(int n, int number) {
		String binary = Integer.toBinaryString(number);
		if ((binary.length() - n) < 0)
			return number;
		String ret = binary.substring(binary.length() - n);
		return Integer.parseInt(ret, 2);

	}

}


