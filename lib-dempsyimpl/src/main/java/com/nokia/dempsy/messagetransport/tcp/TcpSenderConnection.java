package com.nokia.dempsy.messagetransport.tcp;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nokia.dempsy.messagetransport.MessageTransportException;
import com.nokia.dempsy.util.SocketTimeout;

public class TcpSenderConnection implements Runnable
{
   private static Logger logger = LoggerFactory.getLogger(TcpSenderConnection.class);

   private DataOutputStream dataOutputStream = null;
   private List<TcpSender> senders = new ArrayList<TcpSender>();
   
   private enum IsLocalAddress { Yes, No, Unknown };
   private IsLocalAddress isLocalAddress = IsLocalAddress.Unknown;
   private Thread senderThread = null;

   private AtomicBoolean isSenderRunning = new AtomicBoolean(false);
   private AtomicBoolean senderKeepRunning = new AtomicBoolean(false);
   
   private long timeoutMillis;
   protected SocketTimeout socketTimeout = null;
   private long maxNumberOfQueuedMessages;
   private boolean batchOutgoingMessages;
   
   private Socket socket = null;
   protected BlockingQueue<TcpSender.Enqueued> sendingQueue = new LinkedBlockingQueue<TcpSender.Enqueued>();
   protected TcpDestination destination;
   
   protected TcpSenderConnection(TcpDestination baseDestination, long maxNumberOfQueuedOutgoing, 
         long socketWriteTimeoutMillis, boolean batchOutgoingMessages)
   {
      this.timeoutMillis = socketWriteTimeoutMillis;
      this.batchOutgoingMessages = batchOutgoingMessages;
      this.maxNumberOfQueuedMessages = maxNumberOfQueuedOutgoing;
      this.destination = baseDestination;
   }

   protected synchronized void start(TcpSender sender)
   {
      if (senders.size() == 0)
      {
         senderKeepRunning.set(true);
         senderThread = new Thread(this,"TcpSender to " + sender.destination);
         senderThread.setDaemon(true);
         senderThread.start();
         synchronized(isSenderRunning) 
         {
            // if the sender isn't running yet, then wait for it.
            if (!isSenderRunning.get())
            {
               try { isSenderRunning.wait(10000); } catch (InterruptedException ie) {}
            }
            
            if (!isSenderRunning.get())
               logger.error("Failed to start TcpSender thread for " + sender.destination);
         }
      }
      
      senders.add(sender);
   }
   
   protected synchronized void stop(TcpSender sender)
   {
      senders.remove(sender);
      if (senders.size() == 0)
      {
         senderKeepRunning.set(false);
         
         // poll with interrupts, for the thread to exit.
         if (senderThread != null)
         {
            for (int i = 0; i < 3000; i++)
            {
               senderThread.interrupt();
               try { Thread.sleep(1); } catch (InterruptedException ie) {}

               if (!isSenderRunning.get())
                  break;
            }
         }
         
         if (isSenderRunning.get())
         {
            logger.error("Couldn't seem to stop the sender thread. Ignoring.");

            // attempt a cleanup anyway.
            closeQuietly(socket);
            if (socketTimeout != null)
               socketTimeout.stop();
         }
      }
   }

   public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }

   public void setMaxNumberOfQueuedMessages(long maxNumberOfQueuedMessages) { this.maxNumberOfQueuedMessages = maxNumberOfQueuedMessages; }
   
   @Override
   public void run()
   {
      TcpSender.Enqueued message = null;
      try
      {
         synchronized(isSenderRunning)
         {
            isSenderRunning.set(true);
            isSenderRunning.notifyAll();
         }
         
         int ioeFailedCount = -1;
         int queueSizeOnFirstFail = -1;
         
         while (senderKeepRunning.get())
         {
            try
            {
               message = batchOutgoingMessages ? sendingQueue.poll() : sendingQueue.take();
               
               DataOutputStream localDataOutputStream = getDataOutputStream();

               if (message == null)
               {
                  socketTimeout.begin();
                  localDataOutputStream.flush();
                  socketTimeout.end();
                  message = sendingQueue.take();
               }
            
               if (maxNumberOfQueuedMessages < 0 || sendingQueue.size() <= maxNumberOfQueuedMessages)
               {
                  int size = message.messageBytes.length;
                  if (size > Short.MAX_VALUE)
                     size = -1;
                  socketTimeout.begin();
                  localDataOutputStream.write(message.getSequence());
                  localDataOutputStream.writeShort( size );
                  if (size == -1)
                     localDataOutputStream.writeInt(message.messageBytes.length);
                  localDataOutputStream.write( message.messageBytes );
                  if (!batchOutgoingMessages)
                     localDataOutputStream.flush(); // flush individual message

                  ioeFailedCount = -1;
                  socketTimeout.end();

                  message.messageSent();
               }
               else
                  message.messageNotSent();
            }
            catch (IOException ioe)
            {
               socketTimeout.end();
               if (message != null) message.messageNotSent();
               close();
               // This can happen lots of times so let's track it
               if (ioeFailedCount == -1)
               {
                   ioeFailedCount = 0; // this will be incremented to 0
                   queueSizeOnFirstFail = sendingQueue.size();
                   logger.warn("It appears the client " + destination + " is no longer taking calls. This message may be supressed for a while.",ioe);
               }
               else if (ioeFailedCount >= queueSizeOnFirstFail)
                   ioeFailedCount = -1;
               else
                   ioeFailedCount++;
            }
            catch (InterruptedException ie)
            {
               socketTimeout.end();
               if (message != null) message.messageNotSent();
               if (senderKeepRunning.get()) // if we're supposed to be running still, then we're not shutting down. Not sure why we reset.
                  logger.warn("Sending data to " + destination + " was interrupted for no good reason.",ie);
            }
            catch (Throwable th)
            {
               socketTimeout.end();
               if (message != null) message.messageNotSent();
               logger.error("Unknown exception thrown while trying to send a message to " + destination);
            }
         }
      }
      catch (RuntimeException re) { logger.error("Unexpected Exception!",re); }
      finally
      {
         synchronized(this) { senderThread = null; }
         socketTimeout.stop();
         close();
         isSenderRunning.set(false);
      }
   }
   
   // this should ONLY be called from the read thread
   private DataOutputStream getDataOutputStream() throws MessageTransportException, IOException
   {
      if ( dataOutputStream == null) // socket must also be null.
      {
         if (socketTimeout != null)
            socketTimeout.stop();
         
         socket = makeSocket(destination);
         socketTimeout = new SocketTimeout(socket, timeoutMillis);
         
         // There is a really odd circumstance (at least on Linux) where a connection 
         //  to a port in the dynamic range, while there is no listener on that port,
         //  from the same system/network interface, can result in a local port selection
         //  that's the same as the port that the connection attempt is to. In this case,
         //  for some reason the Socket instantiation (and connection) succeeds without
         //  a listener. We need to force a failure if this is the case.
         if (isLocalAddress == IsLocalAddress.Unknown)
         {
            if (socket.isBound())
            {
               InetAddress localSocketAddress = socket.getLocalAddress();
               isLocalAddress = 
                  (Arrays.equals(localSocketAddress.getAddress(),destination.inetAddress.getAddress())) ?
                        IsLocalAddress.Yes : IsLocalAddress.No;
            }
         }
         
         if (isLocalAddress == IsLocalAddress.Yes)
         {
            if (socket.getLocalPort() == destination.port)
               throw new IOException("Connection to self same port!!!");
         }

          dataOutputStream = new DataOutputStream( new BufferedOutputStream(socket.getOutputStream(), 1024 * 1024) );
      }
      
      return dataOutputStream;
   }

   /**
    * This method is here for testing. It allows me to create a fake output stream that 
    * I can disrupt to test the behavior of network failures.
    */
   protected Socket makeSocket(TcpDestination destination) throws IOException
   {
      return new Socket(destination.inetAddress,destination.port); 
   }
   
   protected void closeQuietly(Socket socket) 
   {
      if (socket != null)
      {
         try { socket.close(); } 
         catch (IOException ioe)
         {
            if (logger.isDebugEnabled())
               logger.debug("close socket failed for " + destination); 
         }
         catch (Throwable th) { logger.debug("Socket close resulted in ",th); }
      }
   }

   
   // this ONLY be called from the run thread
   private void close()
   {
      if ( dataOutputStream != null) IOUtils.closeQuietly( dataOutputStream );
      dataOutputStream = null;
      
      closeQuietly(socket); 
      socket = null;
   }
}