package fr.kokhaviel.kvim.api.actions.tools;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.xml.bind.DatatypeConverter;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public class KVimTools {

	public static void uppercaseSelection(KVimTab tab) {
		if(tab.getSelectedText() == null || tab.getSelectedText().equals("")) return;
		final String selectedText = tab.getSelectedText();
		tab.replaceSelection(selectedText.toUpperCase());
	}

	public static void lowercaseSelection(KVimTab tab) {
		if(tab.getSelectedText() == null || tab.getSelectedText().equals("")) return;
		final String selectedText = tab.getSelectedText();
		tab.replaceSelection(selectedText.toLowerCase());
	}

	public static void capitalizeSelection(KVimTab tab) {

		if(tab.getSelectedText() == null || tab.getSelectedText().equals("")) return;
		final String[] selectedText = tab.getSelectedText().split(" ");
		StringBuilder builder = new StringBuilder();

		for(String s : selectedText) {
			builder.append(s.substring(0, 1).toUpperCase()).append(s.substring(1)).append(" ");
		}

		tab.replaceSelection(builder.toString());
	}

	public static void googleSelection(KVimTab tab) throws IOException {
		if(tab.getSelectedText() == null || tab.getSelectedText().equals("")) return;
		final String selectedText = tab.getSelectedText();
		Desktop.getDesktop().browse(URI.create("https://www.google.com/search?q=" + fixURL(selectedText)));
	}

	public static void genRandomUUID(KVimTab tab) throws BadLocationException {
		tab.getStyledDocument().insertString(tab.getCaretPosition(), UUID.randomUUID().toString(), null);
	}

	public static void getMD5Sum(KVimTab tab) {
		if(tab.isUntitled()) return;

		JTextArea area = new JTextArea(1, 32);
		area.setWrapStyleWord(true);
		area.setLineWrap(true);
		area.setCaretPosition(0);
		area.setEditable(false);

		try {
			byte[] b = Files.readAllBytes(tab.getFilePath());
			byte[] hash = MessageDigest.getInstance("MD5").digest(b);

			area.setText(DatatypeConverter.printHexBinary(hash).toUpperCase());
			JOptionPane.showMessageDialog(null, new JScrollPane(area),
					"MD5 Sum", JOptionPane.INFORMATION_MESSAGE);
		} catch(IOException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static void getSHA1Sum(KVimTab tab) {
		if(tab.isUntitled()) return;

		JTextArea area = new JTextArea(1, 48);
		area.setWrapStyleWord(true);
		area.setLineWrap(true);
		area.setCaretPosition(0);
		area.setEditable(false);

		try {
			byte[] b = Files.readAllBytes(tab.getFilePath());
			byte[] hash = MessageDigest.getInstance("SHA1").digest(b);

			area.setText(DatatypeConverter.printHexBinary(hash).toUpperCase());
			JOptionPane.showMessageDialog(null, new JScrollPane(area),
					"SHA1 Sum", JOptionPane.INFORMATION_MESSAGE);
		} catch(IOException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static void getSHA256(KVimTab tab) {
		if(tab.isUntitled()) return;


		JTextArea area = new JTextArea(1, 72);
		area.setWrapStyleWord(true);
		area.setLineWrap(true);
		area.setCaretPosition(0);
		area.setEditable(false);

		try {
			byte[] b = Files.readAllBytes(tab.getFilePath());
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(b);

			area.setText(DatatypeConverter.printHexBinary(hash).toUpperCase());
			JOptionPane.showMessageDialog(null, new JScrollPane(area),
					"SHA256 Sum", JOptionPane.INFORMATION_MESSAGE);
		} catch(IOException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static void getSHA512Sum(KVimTab tab) {
		if(tab.isUntitled()) return;


		JTextArea area = new JTextArea(1, 96);
		area.setWrapStyleWord(true);
		area.setLineWrap(true);
		area.setCaretPosition(0);
		area.setEditable(false);

		try {
			byte[] b = Files.readAllBytes(tab.getFilePath());
			byte[] hash = MessageDigest.getInstance("SHA-512").digest(b);

			area.setText(DatatypeConverter.printHexBinary(hash).toUpperCase());
			JOptionPane.showMessageDialog(null, new JScrollPane(area),
					"SHA512 Sum", JOptionPane.INFORMATION_MESSAGE);
		} catch(IOException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	static String fixURL(String url) {
		return url
				.replace("%", "%25")
				.replace(" ", "%20")
				.replace("!", "%21")
				.replace("\"", "%22")
				.replace("#", "%23")
				.replace("$", "%24")
				.replace("&", "%26")
				.replace("'", "%27")
				.replace("(", "%28")
				.replace(")", "%29")
				.replace("*", "%2A")
				.replace("+", "%2B")
				.replace(",", "%2C")
				.replace("-", "%2D")
				.replace(".", "%2E")
				.replace("/", "%2F")
				.replace(":", "%3A")
				.replace(";", "%3B")
				.replace("<", "%3C")
				.replace("=", "%3D")
				.replace(">", "%3E")
				.replace("?", "%3F")
				.replace("@", "%40")
				.replace("[", "%5B")
				.replace("\\", "%5C")
				.replace("]", "%5D")
				.replace("^", "%5E")
				.replace("_", "%5F")
				.replace("`", "%60")
				.replace("{", "%7B")
				.replace("|", "%7C")
				.replace("}", "%7D")
				.replace("~", "%7E");
	}
}
