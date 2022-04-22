package fr.kokhaviel.kvim.api.actions.file;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.xml.bind.DatatypeConverter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

public class KVimProperties extends JFrame {

	public KVimProperties(Path file) throws IOException, NoSuchAlgorithmException {
		super("File Properties");
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		double width = screenSize.getWidth();
		double height = screenSize.getHeight();
		this.setLocation((int) (width / 3), (int) (height / 3));
		this.add(initFrame(file));
		this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		this.setResizable(false);
		this.pack();
	}

	private JPanel initFrame(Path file) throws IOException, NoSuchAlgorithmException {
		JPanel jPanel = new JPanel();
		jPanel.setLayout(new BorderLayout());
		JPanel left = new JPanel(new GridLayout(0, 1));
		JPanel right = new JPanel(new GridLayout(0, 1));
		jPanel.setBorder(new EmptyBorder(25, 25, 25, 25));


		final File file1 = file.toFile();
		PosixFileAttributes fileAttributeView = Files.readAttributes(file, PosixFileAttributes.class);
		BasicFileAttributes basicFileAttributes = Files.readAttributes(file, BasicFileAttributes.class);
		byte[] b = Files.readAllBytes(file);
		byte[] hash = MessageDigest.getInstance("MD5").digest(b);

		left.add(new JLabel("Name :  "));
		left.add(new JLabel("Path :  "));
		left.add(new JLabel("Size :  "));
		left.add(new JLabel("Last Modification :   "));
		left.add(new JLabel("Owner :  "));
		left.add(new JLabel("Group :  "));
		left.add(new JLabel("Permissions :  "));
		left.add(new JLabel("MD5 Sum :  "));
		right.add(new JLabel(file1.getName()));
		right.add(new JLabel(file1.getPath()));
		right.add(new JLabel(getReadableSize(file1.length())));
		right.add(new JLabel(getDateTimeFromInstant(basicFileAttributes.lastModifiedTime().toInstant())));
		right.add(new JLabel(fileAttributeView.owner().getName()));
		right.add(new JLabel(fileAttributeView.group().getName()));
		right.add(new JLabel(getReadablePerms(fileAttributeView.permissions())));
		right.add(new JLabel(DatatypeConverter.printHexBinary(hash)));

		jPanel.add(left, BorderLayout.WEST);
		jPanel.add(right, BorderLayout.EAST);
		return jPanel;
	}

	public static String getDateTimeFromInstant(Instant instant) {
		LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

		return ldt.getDayOfMonth() + " " + getMonthName(ldt.getMonthValue()) + " " + ldt.getYear() + "  "
				+ ldt.getHour() + ":" + ldt.getMinute() + ":" + ldt.getSecond();
	}

	public static String getReadablePerms(Set<PosixFilePermission> permissions) {
		StringBuilder builder = new StringBuilder("[ ");

		if(permissions.contains(PosixFilePermission.OWNER_READ)) {
			builder.append("r ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.OWNER_WRITE)) {
			builder.append("w ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
			builder.append("x ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.GROUP_READ)) {
			builder.append("r ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.GROUP_WRITE)) {
			builder.append("w ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.GROUP_EXECUTE)) {
			builder.append("x ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.OTHERS_READ)) {
			builder.append("r ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
			builder.append("w ");
		} else {
			builder.append("- ");
		}
		if(permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
			builder.append("x ");
		} else {
			builder.append("- ");
		}
		builder.append("]");
		return builder.toString();
	}

	public static String getMonthName(int value) {
		switch(value) {
			case 1:
				return "January";
			case 2:
				return "February";
			case 3:
				return "March";
			case 4:
				return "April";
			case 5:
				return "May";
			case 6:
				return "June";
			case 7:
				return "July";
			case 8:
				return "August";
			case 9:
				return "September";
			case 10:
				return "October";
			case 11:
				return "November";
			case 12:
				return "December";
			default:
				return "";
		}
	}

	public static String getReadableSize(long size) {
		if(size < 1024) {
			return size + "b";
		} else {
			size /= 1024;
			if(size < 1024) {
				return size + "Kb";
			} else {
				size /= 1024;
				if(size < 1024) {
					return size + "Mb";
				} else {
					size /= 1024;
					if(size < 1024) {
						return size + "Gb";
					} else {
						size /= 1024;
						return size + "Tb";
					}
				}
			}
		}
	}
}
