/**
 * Copyright 2005 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.protocol.ftp;

import javax.activation.MimetypesFileTypeMap;
// 20040528, xing, disabled for now
//import xing.org.apache.nutch.util.magicfile.*;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import org.apache.commons.net.ftp.parser.DefaultFTPFileEntryParserFactory;
import org.apache.commons.net.ftp.parser.ParserInitializationException;

import org.apache.nutch.protocol.Content;

import java.net.InetAddress;
import java.net.URL;

import java.lang.Exception;
import java.lang.StackTraceElement;

import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;
import java.util.Properties;

import java.util.logging.Level;

import java.io.ByteArrayOutputStream;
//import java.io.InputStream;
import java.io.IOException;

/************************************
 * FtpResponse.java mimics ftp replies as http response.
 * It tries its best to follow http's way for headers, response codes
 * as well as exceptions.
 *
 * Comments:
 * In this class, all FtpException*.java thrown by Client.java
 * and some important commons-net exceptions passed by Client.java
 * must have been properly dealt with. They'd better not be leaked
 * to the caller of this class.
 *
 * @author John Xing
 ***********************************/
public class FtpResponse {
  private String orig;
  private String base;
  private byte[] content;
  private int code;
  private Properties headers = new Properties();

  private final Ftp ftp;

  /** Returns the response code. */
  public int getCode() { return code; }

  /** Returns the value of a named header. */
  public String getHeader(String name) {
    return (String)headers.get(name);
  }

  public byte[] getContent() { return content; }

  public Content toContent() {
    return new Content(orig, base, content,
                       getHeader("Content-Type"),
                       headers);
  }

  public FtpResponse(URL url, Ftp ftp)
    throws FtpException, IOException {
    this(url.toString(), url, ftp);
  }

  public FtpResponse(String orig, URL url, Ftp ftp)
    throws FtpException, IOException {

    this.orig = orig;
    this.base = url.toString();
    this.ftp = ftp;

    if (!"ftp".equals(url.getProtocol()))
      throw new FtpException("Not a ftp url:" + url);

    if (url.getPath() != url.getFile())
      Ftp.LOG.warning("url.getPath() != url.getFile(): " + url);

    String path = "".equals(url.getPath()) ? "/" : url.getPath();

    try {

      if (ftp.followTalk) {
        Ftp.LOG.info("fetching "+url);
      } else {
        if (Ftp.LOG.isLoggable(Level.FINE))
          Ftp.LOG.fine("fetching "+url);
      }

      InetAddress addr = InetAddress.getByName(url.getHost());

      // idled too long, remote server or ourselves may have timed out,
      // should start anew.
      if (ftp.client != null && ftp.keepConnection
          && ftp.renewalTime < System.currentTimeMillis()) {
        Ftp.LOG.info("delete client because idled too long");
        ftp.client = null;
      }

      // start anew if needed
      if (ftp.client == null) {
        if (ftp.followTalk)
          Ftp.LOG.info("start client");
        // the real client
        ftp.client = new Client();
        // when to renew, take the lesser
        //ftp.renewalTime = System.currentTimeMillis()
        //  + ((ftp.timeout<ftp.serverTimeout) ? ftp.timeout : ftp.serverTimeout);

        // timeout for control connection
        ftp.client.setDefaultTimeout(ftp.timeout);
        // timeout for data connection
        ftp.client.setDataTimeout(ftp.timeout);

        // follow ftp talk?
        if (ftp.followTalk)
          ftp.client.addProtocolCommandListener(
            new PrintCommandListener(ftp.LOG));
      }

      // quit from previous site if at a different site now
      if (ftp.client.isConnected()) {
        InetAddress remoteAddress = ftp.client.getRemoteAddress();
        if (!addr.equals(remoteAddress)) {
          if (ftp.followTalk)
            Ftp.LOG.info("disconnect from "+remoteAddress
            +" before connect to "+addr);
          // quit from current site
          ftp.client.logout();
          ftp.client.disconnect();
        }
      }

      // connect to current site if needed
      if (!ftp.client.isConnected()) {

        if (ftp.followTalk)
          Ftp.LOG.info("connect to "+addr);

        ftp.client.connect(addr);
        if (!FTPReply.isPositiveCompletion(ftp.client.getReplyCode())) {
          ftp.client.disconnect();
          Ftp.LOG.warning("ftp.client.connect() failed: "
            + addr + " " + ftp.client.getReplyString());
          this.code = 500; // http Internal Server Error
          return;
        }

        if (ftp.followTalk)
          Ftp.LOG.info("log into "+addr);

        if (!ftp.client.login(ftp.userName, ftp.passWord)) {
          // login failed.
          // please note that some server may return 421 immediately
          // after USER anonymous, thus ftp.client.login() won't return false,
          // but throw exception, which then will be handled by caller
          // (not dealt with here at all) .
          ftp.client.disconnect();
          Ftp.LOG.warning("ftp.client.login() failed: "+addr);
          this.code = 401;  // http Unauthorized
          return;
        }

        // insist on binary file type
        if (!ftp.client.setFileType(FTP.BINARY_FILE_TYPE)) {
          ftp.client.logout();
          ftp.client.disconnect();
          Ftp.LOG.warning("ftp.client.setFileType() failed: "+addr);
          this.code = 500; // http Internal Server Error
          return;
        }

        if (ftp.followTalk)
          Ftp.LOG.info("set parser for "+addr);

        // SYST is valid only after login
        try {
          ftp.parser = null;
          String parserKey = ftp.client.getSystemName();
          // some server reports as UNKNOWN Type: L8, but in fact UNIX Type: L8
          if (parserKey.startsWith("UNKNOWN Type: L8"))
            parserKey = "UNIX Type: L8";
          ftp.parser = (new DefaultFTPFileEntryParserFactory())
            .createFileEntryParser(parserKey);
        } catch (FtpExceptionBadSystResponse e) {
          Ftp.LOG.warning("ftp.client.getSystemName() failed: "+addr+" "+e);
          ftp.parser = null;
        } catch (ParserInitializationException e) {
          // ParserInitializationException is RuntimeException defined in
          // org.apache.commons.net.ftp.parser.ParserInitializationException
          Ftp.LOG.warning("createFileEntryParser() failed. "+addr+" "+e);
          ftp.parser = null;
        } finally {
          if (ftp.parser == null) {
            // do not log as severe, otherwise
            // FetcherThread/RequestScheduler will abort
            Ftp.LOG.warning("ftp.parser is null: "+addr);
            ftp.client.logout();
            ftp.client.disconnect();
            this.code = 500; // http Internal Server Error
            return;
          }
        }

      } else {
        if (ftp.followTalk)
          Ftp.LOG.info("use existing connection");
      }

      this.content = null;

      if (path.endsWith("/")) {
        getDirAsHttpResponse(path);
      } else {
        getFileAsHttpResponse(path);
      }

      // reset next renewalTime, take the lesser
      if (ftp.client != null && ftp.keepConnection) {
        ftp.renewalTime = System.currentTimeMillis()
          + ((ftp.timeout<ftp.serverTimeout) ? ftp.timeout : ftp.serverTimeout);
        if (ftp.followTalk)
          Ftp.LOG.info("reset renewalTime to "
            +ftp.httpDateFormat.toString(ftp.renewalTime));
      }

      // getDirAsHttpResponse() or getFileAsHttpResponse() above
      // may have deleted ftp.client
      if (ftp.client != null && !ftp.keepConnection) {
        if (ftp.followTalk)
          Ftp.LOG.info("disconnect from "+addr);
        ftp.client.logout();
        ftp.client.disconnect();
      }
      
    } catch (Exception e) {
      ftp.LOG.warning(""+e);
      StackTraceElement stes[] = e.getStackTrace();
      for (int i=0; i<stes.length; i++) {
        ftp.LOG.warning("   "+stes[i].toString());
      }
      // for any un-foreseen exception (run time exception or not),
      // do ultimate clean and leave ftp.client for garbage collection
      if (ftp.followTalk)
        Ftp.LOG.info("delete client due to exception");
      ftp.client = null;
      // or do explicit garbage collection?
      // System.gc();
// can we be less dramatic, using the following instead?
// probably unnecessary for our practical purpose here
//      try {
//        ftp.client.logout();
//        ftp.client.disconnect();
//      }
      throw new FtpException(e);
      //throw e;
    }

  }

  // get ftp file as http response
  private void getFileAsHttpResponse(String path)
    throws IOException {

    ByteArrayOutputStream os = null;
    List list = null;

    try {
      // first get its possible attributes
      list = new LinkedList();
      ftp.client.retrieveList(path, list, ftp.maxContentLength, ftp.parser);

      os = new ByteArrayOutputStream(ftp.BUFFER_SIZE);
      ftp.client.retrieveFile(path, os, ftp.maxContentLength);

      FTPFile ftpFile = (FTPFile) list.get(0);
      this.headers.put("Content-Length",
        new Long(ftpFile.getSize()).toString());
      //this.headers.put("content-type", "text/html");
      this.headers.put("Last-Modified",
        ftp.httpDateFormat.toString(ftpFile.getTimestamp()));
      this.content = os.toByteArray();

      String contentType = null;
      // 20040427, xing, disabled for now
      //if (contentType == null && ftp.magic != null)
      //  contentType = ftp.magic.getMimeType(this.content);
      if (contentType == null && ftp.TYPE_MAP != null)
        contentType = ftp.TYPE_MAP.getContentType(path);
      if (contentType != null)
        this.headers.put("Content-Type", contentType);

//      // approximate bytes sent and read
//      if (this.httpAccounting != null) {
//        this.httpAccounting.incrementBytesSent(path.length());
//        this.httpAccounting.incrementBytesRead(this.content.length);
//      }

      this.code = 200; // http OK

    } catch (FtpExceptionControlClosedByForcedDataClose e) {

      // control connection is off, clean up
      // ftp.client.disconnect();
      if (ftp.followTalk)
        Ftp.LOG.info("delete client because server cut off control channel: "+e);
      ftp.client = null;

      // in case this FtpExceptionControlClosedByForcedDataClose is
      // thrown by retrieveList() (not retrieveFile()) above,
      if (os == null) { // indicating throwing by retrieveList()
        //throw new FtpException("fail to get attibutes: "+path);
        Ftp.LOG.warning(
            "Please try larger maxContentLength for ftp.client.retrieveList(). "
          + e);
        // in a way, this is our request fault
        this.code = 400;  // http Bad request
        return;
      }

      FTPFile ftpFile = (FTPFile) list.get(0);
      this.headers.put("Content-Length",
        new Long(ftpFile.getSize()).toString());
      //this.headers.put("content-type", "text/html");
      this.headers.put("Last-Modified",
        ftp.httpDateFormat.toString(ftpFile.getTimestamp()));
      this.content = os.toByteArray();

      String contentType = null;
      // 20040427, xing, disabled for now
      //if (contentType == null && ftp.magic != null)
      //  contentType = ftp.magic.getMimeType(this.content);
      if (contentType == null && ftp.TYPE_MAP != null)
        contentType = ftp.TYPE_MAP.getContentType(path);
      if (contentType != null)
        this.headers.put("Content-Type", contentType);

//      // approximate bytes sent and read
//      if (this.httpAccounting != null) {
//        this.httpAccounting.incrementBytesSent(path.length());
//        this.httpAccounting.incrementBytesRead(this.content.length);
//      }

      this.code = 200; // http OK

    } catch (FtpExceptionCanNotHaveDataConnection e) {

      if (FTPReply.isPositiveCompletion(ftp.client.cwd(path))) {
      // it is not a file, but dir, so redirect as a dir
        this.headers.put("Location", path + "/");
        this.code = 300;  // http redirect
        // fixme, should we do ftp.client.cwd("/"), back to top dir?
      } else {
      // it is not a dir either
        this.code = 404;  // http Not Found
      }

    } catch (FtpExceptionUnknownForcedDataClose e) {
      // Please note control channel is still live.
      // in a way, this is our request fault
      Ftp.LOG.warning(
          "Unrecognized reply after forced close of data channel. "
        + "If this is acceptable, please modify Client.java accordingly. "
        + e);
      this.code = 400; // http Bad Request
    }

  }

  // get ftp dir list as http response
  private void getDirAsHttpResponse(String path)
    throws IOException {
    List list = new LinkedList();

    try {

      // change to that dir first
      if (!FTPReply.isPositiveCompletion(ftp.client.cwd(path))) {
        this.code = 404;  // http Not Found
        return;
      }

      // fixme, should we do ftp.client.cwd("/"), back to top dir?

      ftp.client.retrieveList(null, list, ftp.maxContentLength, ftp.parser);
      this.content = list2html(list, path, "/".equals(path) ? false : true);
      this.headers.put("Content-Length",
        new Integer(this.content.length).toString());
      this.headers.put("Content-Type", "text/html");
      // this.headers.put("Last-Modified", null);

//      // approximate bytes sent and read
//      if (this.httpAccounting != null) {
//        this.httpAccounting.incrementBytesSent(path.length());
//        this.httpAccounting.incrementBytesRead(this.content.length);
//      }

      this.code = 200; // http OK

    } catch (FtpExceptionControlClosedByForcedDataClose e) {

      // control connection is off, clean up
      // ftp.client.disconnect();
      if (ftp.followTalk)
        Ftp.LOG.info("delete client because server cut off control channel: "+e);
      ftp.client = null;

      this.content = list2html(list, path, "/".equals(path) ? false : true);
      this.headers.put("Content-Length",
        new Integer(this.content.length).toString());
      this.headers.put("Content-Type", "text/html");
      // this.headers.put("Last-Modified", null);

//      // approximate bytes sent and read
//      if (this.httpAccounting != null) {
//        this.httpAccounting.incrementBytesSent(path.length());
//        this.httpAccounting.incrementBytesRead(this.content.length);
//      }

      this.code = 200; // http OK

    } catch (FtpExceptionUnknownForcedDataClose e) {
      // Please note control channel is still live.
      // in a way, this is our request fault
      Ftp.LOG.warning(
          "Unrecognized reply after forced close of data channel. "
        + "If this is acceptable, please modify Client.java accordingly. "
        + e);
      this.code = 400; // http Bad Request
    } catch (FtpExceptionCanNotHaveDataConnection e) {
      Ftp.LOG.warning(""+ e);
      this.code = 500; // http Iternal Server Error
    }

  }

  // generate html page from ftp dir list
  private byte[] list2html(List list, String path, boolean includeDotDot) {

    //StringBuffer x = new StringBuffer("<!doctype html public \"-//ietf//dtd html//en\"><html><head>");
    StringBuffer x = new StringBuffer("<html><head>");
    x.append("<title>Index of "+path+"</title></head>\n");
    x.append("<body><h1>Index of "+path+"</h1><pre>\n");

    if (includeDotDot) {
      x.append("<a href='../'>../</a>\t-\t-\t-\n");
    }

    for (int i=0; i<list.size(); i++) {
      FTPFile f = (FTPFile) list.get(i);
      String name = f.getName();
      String time = ftp.httpDateFormat.toString(f.getTimestamp());
      if (f.isDirectory()) {
        // some ftp server LIST "." and "..", we skip them here
        if (name.equals(".") || name.equals(".."))
          continue;
        x.append("<a href='"+name+"/"+"'>"+name+"/</a>\t");
        x.append(time+"\t-\n");
      } else if (f.isFile()) {
        x.append("<a href='"+name+    "'>"+name+"</a>\t");
        x.append(time+"\t"+f.getSize()+"\n");
      } else {
        // ignore isSymbolicLink()
        // ignore isUnknown()
      }
    }

    x.append("</pre></body></html>\n");

    return new String(x).getBytes();
  }

}