/*
 * Copyright (C) 2020 Azat Sharipov <azatsh@yandex.ru>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.azatsh.sshpasschange;

import static net.sf.expectit.matcher.Matchers.contains;
import static net.sf.expectit.matcher.Matchers.regexp;
import static net.sf.expectit.matcher.Matchers.sequence;

import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.sf.expectit.Expect;
import net.sf.expectit.ExpectBuilder;
import net.sf.expectit.ExpectIOException;
import net.sf.expectit.Result;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
public class Main {

  private static Properties props;
  private static final String IGNORE_ERRORS_PROP = "ignore_errors";

  private static final Pattern HELP_PARAMETER_PATTERN = Pattern.compile("^-+((\\bh\\b)|(\\bhelp\\b))");

  private static final String USAGE = "%nUsage:%n"
      + "  java -cp {classpath} org.azatsh.sshpasschange.Main {filename}%n"
      + "  java -cp {classpath} org.azatsh.sshpasschange.Main {username} {password} {new_password} [hosts]%n"
      + "Options:%n"
      + "  filename - name of file containing lines as: {username} {password} {new_password} [hosts]%n"
      + "  username - user name (to connect via ssh)%n"
      + "  password - user password (to connect via ssh)%n"
      + "  new_password - user new password%n"
      + "  hosts - comma separated host IPs/names (optional, can be set in settings.properties)%n"
      + "Examples:%n"
      + "  java -cp \"./:./libs/*\" org.azatsh.sshpasschange.Main users_to_update.txt%n"
      + "  java -cp \"./:./libs/*\" org.azatsh.sshpasschange.Main testuser qwerty qwerty123%n"
      + "  java -cp \"./:./libs/*\" org.azatsh.sshpasschange.Main testuser qwerty qwerty123 10.18.40.30,10.18.40.31%n";

  public static void main(String[] args) {
    new Main().start(args);
  }

  private void start(String[] args) {
    if (args.length == 0 || args.length == 2 || args.length > 4 || isHelpParameter(args[0])) {
      printUsage();
      System.exit(1);
    }

    boolean isSuccess;
    if (args.length == 1) { // assuming a file is provided
      val filePath = Paths.get(args[0]);
      if (!filePath.toFile().exists()) {
        printMessage("The file provided does not exist");
        System.exit(1);
      }
      isSuccess = processFile(filePath);
    } else { // user, pass and newPass must be provided
      val username = args[0];
      val password = args[1];
      val newPassword = args[2];
      val hosts = args.length > 3 ? args[3] : getProperty("hosts");
      isSuccess = processHosts(hosts, username, password, newPassword);
    }

    printMessage("");
    if (!isSuccess) {
      printMessage("There were some errors, please check logs.");
      if (notIgnoreErrors()) {
        printMessage("The process stopped due to '" + IGNORE_ERRORS_PROP + "' property is set false");
      }
    }
    if (isSuccess || !notIgnoreErrors()) {
      printMessage(
          "Password change completed" + (isSuccess ? " successfully" : " with errors") + ".");
    }
  }

  private boolean processFile(Path file) {
    boolean isSuccess = true;

    try (val reader = new LineNumberReader(new FileReader(file.toFile()))) {
      String line = reader.readLine();
      for (; line != null; line = reader.readLine()) {
        val result = processLine(line, reader.getLineNumber());
        if (!result) {
          isSuccess = false;
          if (notIgnoreErrors()) {
            break;
          }
        }
      }
    } catch (IOException e) {
      log.error("An error loading file", e);
      isSuccess = false;
    }

    return isSuccess;
  }

  private boolean processLine(String line, int lineNumber) {
    boolean isSuccess = true;

    line = line.replaceAll("^\\s+", ""); // remove beginning spaces
    if (!line.isEmpty() && !line.startsWith("#")) { // ignore commented lines (starting from #)
      val parts = line.split("\\s+");
      if (parts.length < 3 || parts.length > 4) {
        log.error(
            "Incorrect data format (line #{}). The format must be: username password new_password hosts",
            lineNumber);
        return false;
      }

      val username = parts[0];
      val password = parts[1];
      val newPassword = parts[2];
      val hosts = parts.length > 3 ? parts[3] : getProperty("hosts");
      isSuccess = processHosts(hosts, username, password, newPassword);
    }

    return isSuccess;
  }

  private boolean processHosts(String hosts, String username, String password,
      String newPassword) {
    if (hosts == null || hosts.isEmpty()) {
      log.error("Hosts must be provided");
      return false;
    }

    boolean isSuccess = true;
    val tokenizer = new StringTokenizer(hosts, ",");
    while (tokenizer.hasMoreTokens()) {
      val host = tokenizer.nextToken();
      try {
        changePass(host, username, password, newPassword);
      } catch (Exception e) {
        log.error(String.format("An error occurred while changing password for %s@%s", username, host), e);
        isSuccess = false;
        if (notIgnoreErrors()) {
          break;
        }
      }
    }

    return isSuccess;
  }

  private void changePass(String host, String username, String password, String newPassword) throws IOException {
    printMessage(String.format("%nIs about to change password for %s@%s", username, host));

    try (val ssh = new SSHClient()) {
      ssh.addHostKeyVerifier((s, i, publicKey) -> true); // disable host key verification
      ssh.connect(host);
      ssh.authPassword(username, password);

      try (Session session = ssh.startSession()) {
        session.allocateDefaultPTY();
        val shell = session.startShell();
        val expectBuilder = new ExpectBuilder()
            .withOutput(shell.getOutputStream())
            .withInputs(shell.getInputStream(), shell.getErrorStream());
        if (getBooleanProperty("enable_echo_output", true)) {
          expectBuilder.withEchoInput(System.out);
        }
        if (getBooleanProperty("enable_echo_input", false)) {
          expectBuilder.withEchoOutput(System.out);
        }
        //expectBuilder.withInputFilters(removeColors(), removeNonPrintable())
        expectBuilder.withTimeout(getIntProperty("expect_timeout", 3000), TimeUnit.MILLISECONDS)
            .withExceptionOnFailure();
        try (Expect expect = expectBuilder.build()) {
          Result result = null;
          try {
            result = expect.expect(contains("$"));
          } catch (ExpectIOException e) {
            log.error("Expect failed", e);
          } catch (IOException | AssertionError e) {
            throw new IllegalStateException(String.format("IO error occurred while establishing connection to %s@%s", username, host), e);
          }

          if (result != null && result.isSuccessful()) {
            // run password change command
            expect.send("passwd" + System.lineSeparator());
          }

          // we expect that password change is prompted either due to 'passwd' command
          // or user's current password expiration
          try {
            expect.expect(regexp("(?i)password:"));
            expect.send(password + System.lineSeparator());
            expect.expect(sequence(regexp("(?i)new"), regexp("(?i)password:")));
            expect.send(newPassword + System.lineSeparator());
            expect.expect(sequence(regexp("(?i)retype"), regexp("(?i)new"), regexp("(?i)password:")));
            expect.send(newPassword + System.lineSeparator());

            expect.expect(contains("updated successfully"));
          } catch (ExpectIOException e) {
            throw new IllegalStateException("Expect operation failed", e);
          } catch (Exception | AssertionError e) {
            throw new IllegalStateException(String.format("An error occurred while working on %s@%s", username, host), e);
          }
        }
      }
    }
  }

  private boolean isHelpParameter(String param) {
    return HELP_PARAMETER_PATTERN.matcher(param).matches();
  }

  private void printUsage() {
    System.out.println(
        String.format(USAGE) // to replace OS specific line endings
    );
  }

  private void printMessage(String msg) {
    System.out.println(msg); // print to console
    if (!msg.isEmpty()) {
      log.info(msg); // duplicate the same to log file
    }
  }

  private String getProperty(String name) {
    if (props == null) {
      props = new Properties();
      try (val inputStream = Main.class.getResourceAsStream("/settings.properties")) {
        props.load(inputStream);
      } catch (Exception e) {
        log.error("An error loading properties", e);
      }
    }
    return props.getProperty(name);
  }

  private int getIntProperty(String name, int defaultValue) {
    val prop = getProperty(name);
    return prop == null || prop.isEmpty() ? defaultValue : Integer.parseInt(prop);
  }

  private boolean getBooleanProperty(String name, boolean defaultValue) {
    val prop = getProperty(name);
    return prop == null || prop.isEmpty() ? defaultValue :
        "true".equalsIgnoreCase(prop) || "yes".equalsIgnoreCase(prop) || "y"
            .equalsIgnoreCase(prop);
  }

  private boolean notIgnoreErrors() {
    return !getBooleanProperty(IGNORE_ERRORS_PROP, true);
  }

}
