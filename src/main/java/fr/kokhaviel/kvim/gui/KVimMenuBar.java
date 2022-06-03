package fr.kokhaviel.kvim.gui;

import fr.kokhaviel.kvim.api.FileType;
import fr.kokhaviel.kvim.api.actions.FileWatcher;
import fr.kokhaviel.kvim.api.actions.edit.*;
import fr.kokhaviel.kvim.api.actions.file.*;
import fr.kokhaviel.kvim.api.actions.todos.KVimTODO;
import fr.kokhaviel.kvim.api.actions.tools.KVimTools;
import fr.kokhaviel.kvim.api.git.*;
import fr.kokhaviel.kvim.api.gui.KVimNewMenuItem;
import fr.kokhaviel.kvim.api.gui.KVimTab;
import fr.kokhaviel.kvim.gui.split.KVimSplitTab;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;

import javax.swing.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Timer;
import java.util.*;

import static fr.kokhaviel.kvim.api.actions.file.KVimNewFile.createUntitledTab;
import static fr.kokhaviel.kvim.gui.KVimMain.*;

public class KVimMenuBar extends JMenuBar {


	CaretListener listener = new CaretListener() {
		@Override
		public void caretUpdate(CaretEvent caretEvent) {
			if(isSplit) {
				kVimMain.updateSplit(
						KVimSplitTab.curLeftTab.getIndex(), KVimSplitTab.curRightTab.getIndex(), KVimSplitTab.curOrientation);
			} else {
				kVimMain.updateTab(curTab.getIndex(), false);
			}
		}
	};

	public static boolean isOverwriteModeEnabled;
	public static boolean isSideBarEnabled;
	public static boolean isAutoReloadEnabled;
	public static boolean isProjectBarEnabled;

	KVimTab curTab;

	//TODO : Add GITHUB Menu
	//TODO : Add Icon Images
	//TODO : Create Project Btn

	JMenu fileBtn = new JMenu("File");
	JMenu editBtn = new JMenu("Edit");
	JMenu viewBtn = new JMenu("View");
	JMenu gitBtn = new JMenu("Git");
	JMenu toolsBtn = new JMenu("Tools");
	JMenu projBtn = new JMenu("Projects");
	JMenu helpBtn = new JMenu("Help");

	//File Menu
	JMenu newBtn = new JMenu("New");
	JMenuItem openBtn = new JMenuItem("Open");
	JMenu openRecBtn = new JMenu("Open Recent");
	JMenuItem saveBtn = new JMenuItem("Save");
	JMenuItem mvBtn = new JMenuItem("Move File");
	JMenuItem cpBtn = new JMenuItem("Copy File");
	JMenuItem reloadBtn = new JMenuItem("Reload");
	JMenuItem deleteBtn = new JMenuItem("Delete");
	JMenuItem cpPathBtn = new JMenuItem("Copy File Path");
	JMenuItem openDirBtn = new JMenuItem("Open Containing Dir");
	JMenuItem propsBtn = new JMenuItem("File properties");
	JMenuItem restartBtn = new JMenuItem("Restart KVim");
	JMenuItem quitBtn = new JMenuItem("Quit KVim");

	//File/New Menu
	JMenuItem untNew = new JMenuItem("Untitled");
	JMenuItem txtNew = new KVimNewMenuItem("File", FileType.TEXT);
	JMenuItem javaNew = new KVimNewMenuItem("Java File", FileType.JAVA);
	JMenuItem ktNew = new KVimNewMenuItem("Kotlin File", FileType.KOTLIN);
	JMenuItem phpNew = new KVimNewMenuItem("PHP File", FileType.PHP);
	JMenuItem pyNew = new KVimNewMenuItem("Python File", FileType.PYTHON);
	JMenuItem htmlNew = new KVimNewMenuItem("HTML File", FileType.HTML);
	JMenuItem cssNew = new KVimNewMenuItem("CSS File", FileType.CSS);
	JMenuItem jsNew = new KVimNewMenuItem("JavaScript File", FileType.JAVASCRIPT);
	JMenuItem cNew = new KVimNewMenuItem("C File", FileType.C);
	JMenuItem cppNew = new KVimNewMenuItem("C++ File", FileType.CPP);
	JMenuItem csNew = new KVimNewMenuItem("C# File", FileType.CSHARP);
	JMenuItem hNew = new KVimNewMenuItem("C Header", FileType.H);
	JMenuItem sqlNew = new KVimNewMenuItem("SQL File", FileType.SQL);
	JMenuItem shNew = new KVimNewMenuItem("Shell Script", FileType.SHELL);
	JMenuItem goNew = new KVimNewMenuItem("Go File", FileType.GO);
	JMenuItem rbNew = new KVimNewMenuItem("Ruby File", FileType.RUBY);

	//Edit Menu
	JMenuItem cutBtn = new JMenuItem("Cut");
	JMenuItem copyBtn = new JMenuItem("Copy");
	JMenuItem pasteBtn = new JMenuItem("Paste");
	JMenuItem findBtn = new JMenuItem("Find");
	JMenuItem rplBtn = new JMenuItem("Replace");
	JMenuItem symBtn = new JMenuItem("Insert Symbol");

	{ //TODO : Insert Symbols Table
		symBtn.setEnabled(false);
	}

	JMenuItem selAllBtn = new JMenuItem("Select All");
	JMenuItem deselectBtn = new JMenuItem("Deselect");
	JCheckBox ovrModBtn = new JCheckBox("Overwrite Mode");
	JMenuItem delLineBtn = new JMenuItem("Delete Line");
	JMenuItem dupLineBtn = new JMenuItem("Duplicate Line");
	JMenuItem swUpBtn = new JMenuItem("Swap Up Line");
	JMenuItem swDownBtn = new JMenuItem("Swap Down Line");

	//View Menu
	JMenuItem prevTabBtn = new JMenuItem("Previous Tab");
	JMenuItem nextTabBtn = new JMenuItem("Next Tab");
	JCheckBox autoRlBtn = new JCheckBox("Auto Reload Document");
	JMenuItem splVertBtn = new JMenuItem("Split Vertical");
	JMenuItem splHorizBtn = new JMenuItem("Split Horizontal");
	JMenuItem closeCurViewBtn = new JMenuItem("Close Current View");
	JMenuItem closeOthViewBtn = new JMenuItem("Close Other Views");
	JCheckBox swSidebarBtn = new JCheckBox("Show/Hide Sidebar");
	JMenuItem gotoLine = new JMenuItem("Goto Line");

	//Projects Menu
	JCheckBox swProjectBar = new JCheckBox("Show Project Explorer");
	JMenuItem todoBtn = new JMenuItem("Project TODO list");

	//Git Menu
	JMenuItem gitInitBtn = new JMenuItem("Git Init");
	JMenuItem gitCloneBtn = new JMenuItem("Git Clone");
	JMenuItem gitAddBtn = new JMenuItem("Git Add");
	JMenu gitBranchBtn = new JMenu("Git Branch");
	JMenuItem gitBranchAddBtn = new JMenuItem("Add Branch");
	JMenuItem gitBranchDelBtn = new JMenuItem("Delete Branch");
	JMenu gitCheckoutBtn = new JMenu("Git Checkout");
	JMenuItem gitCommitBtn = new JMenuItem("Git Commit");
	JMenuItem gitDiffBtn = new JMenuItem("Git Diff");
	JMenuItem gitFetchBtn = new JMenuItem("Git Fetch");
	JMenuItem gitLogBtn = new JMenuItem("Git Log");
	JMenuItem gitMergeBtn = new JMenuItem("Git Merge");
	JMenuItem gitPullBtn = new JMenuItem("Git Pull");
	JMenuItem gitPushBtn = new JMenuItem("Git Push");
	JMenuItem gitRmBtn = new JMenuItem("Git Remove");
	JMenuItem gitStatusBtn = new JMenuItem("Git Status");
	JMenuItem gitTagBtn = new JMenuItem("Git Tag");

	//Tools Menu
	JMenuItem upText = new JMenuItem("Uppercase Selection");
	JMenuItem lowText = new JMenuItem("Lowercase Selection");
	JMenuItem capText = new JMenuItem("Capitalize Selection");
	JMenuItem googleText = new JMenuItem("Google Selection");
	JMenuItem insUUID = new JMenuItem("Insert Random UUID");
	JMenu checksum = new JMenu("Checksum");

	JMenuItem md5 = new JMenuItem("MD5");
	JMenuItem sha1 = new JMenuItem("SHA1");
	JMenuItem sha256 = new JMenuItem("SHA256");
	JMenuItem sha512 = new JMenuItem("SHA512");

	//Help Menu
	JMenuItem whatsThis = new JMenuItem("What's This ?");
	JMenuItem reportBug = new JMenuItem("Report Bug ...");
	JMenuItem about = new JMenuItem("About KVim");

	{
		fileBtn.setMnemonic('f');
		editBtn.setMnemonic('e');
		viewBtn.setMnemonic('v');
		projBtn.setMnemonic('p');
		gitBtn.setMnemonic('g');
		toolsBtn.setMnemonic('t');
		helpBtn.setMnemonic('h');

		newBtn.setMnemonic('n');
		openBtn.setMnemonic('o');
		openRecBtn.setMnemonic('t');
		saveBtn.setMnemonic('s');
		mvBtn.setMnemonic('m');
		cpBtn.setMnemonic('c');
		reloadBtn.setMnemonic('l');
		deleteBtn.setMnemonic('d');
		cpPathBtn.setMnemonic('p');
		openDirBtn.setMnemonic('e');
		propsBtn.setMnemonic('f');
		restartBtn.setMnemonic('r');
		quitBtn.setMnemonic('q');

		untNew.setMnemonic('u');
		txtNew.setMnemonic('f');
		javaNew.setMnemonic('j');
		ktNew.setMnemonic('k');
		phpNew.setMnemonic('p');
		pyNew.setMnemonic('y');
		htmlNew.setMnemonic('t');
		cssNew.setMnemonic('d');
		jsNew.setMnemonic('v');
		cNew.setMnemonic('c');
		cppNew.setMnemonic('o');
		csNew.setMnemonic('a');
		hNew.setMnemonic('h');
		sqlNew.setMnemonic('s');
		shNew.setMnemonic('r');
		goNew.setMnemonic('g');
		rbNew.setMnemonic('b');

		cutBtn.setMnemonic('x');
		copyBtn.setMnemonic('c');
		pasteBtn.setMnemonic('v');
		findBtn.setMnemonic('f');
		rplBtn.setMnemonic('r');
		symBtn.setMnemonic('s');
		selAllBtn.setMnemonic('a');
		deselectBtn.setMnemonic('t');
		ovrModBtn.setMnemonic('o');
		delLineBtn.setMnemonic('l');
		dupLineBtn.setMnemonic('d');
		swUpBtn.setMnemonic('u');
		swDownBtn.setMnemonic('w');

		prevTabBtn.setMnemonic('p');
		nextTabBtn.setMnemonic('n');
		autoRlBtn.setMnemonic('r');
		splVertBtn.setMnemonic('v');
		splHorizBtn.setMnemonic('h');
		closeCurViewBtn.setMnemonic('c');
		closeOthViewBtn.setMnemonic('o');
		swSidebarBtn.setMnemonic('s');
		gotoLine.setMnemonic('g');

		swProjectBar.setMnemonic('e');
		todoBtn.setMnemonic('t');

		gitInitBtn.setMnemonic('i');
		gitCloneBtn.setMnemonic('o');
		gitAddBtn.setMnemonic('a');
		gitBranchBtn.setMnemonic('b');
		gitBranchAddBtn.setMnemonic('a');
		gitBranchDelBtn.setMnemonic('d');
		gitCheckoutBtn.setMnemonic('h');
		gitCommitBtn.setMnemonic('c');
		gitDiffBtn.setMnemonic('d');
		gitFetchBtn.setMnemonic('f');
		gitLogBtn.setMnemonic('l');
		gitMergeBtn.setMnemonic('m');
		gitPullBtn.setMnemonic('p');
		gitPushBtn.setMnemonic('u');
		gitRmBtn.setMnemonic('r');
		gitStatusBtn.setMnemonic('s');
		gitTagBtn.setMnemonic('t');

		upText.setMnemonic('u');
		lowText.setMnemonic('l');
		capText.setMnemonic('c');
		googleText.setMnemonic('g');
		insUUID.setMnemonic('i');
		checksum.setMnemonic('s');
		md5.setMnemonic('m');
		sha1.setMnemonic('1');
		sha256.setMnemonic('2');
		sha512.setMnemonic('5');

		whatsThis.setMnemonic('w');
		reportBug.setMnemonic('r');
		about.setMnemonic('a');

		openBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
		saveBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK));
		mvBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK));
		deleteBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, KeyEvent.SHIFT_DOWN_MASK));
		propsBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK));
		quitBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK));

		selAllBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK));
		cutBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK));
		copyBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK));
		pasteBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK));
		delLineBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, KeyEvent.ALT_DOWN_MASK));
		dupLineBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK));
		swUpBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK));
		swDownBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK));
		findBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.ALT_DOWN_MASK));
		rplBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.ALT_DOWN_MASK));

		prevTabBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK));
		nextTabBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK));
		gotoLine.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK));

		todoBtn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK));

		upText.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK));
		lowText.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK));
		capText.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.ALT_DOWN_MASK));
		googleText.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.ALT_DOWN_MASK));
		insUUID.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, KeyEvent.ALT_DOWN_MASK));

		about.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.ALT_DOWN_MASK));

		reloadBtn.setAccelerator(KeyStroke.getKeyStroke("control shift R"));
		restartBtn.setAccelerator(KeyStroke.getKeyStroke("control alt R"));
		splHorizBtn.setAccelerator(KeyStroke.getKeyStroke("control shift H"));
		splVertBtn.setAccelerator(KeyStroke.getKeyStroke("control shift V"));
		closeCurViewBtn.setAccelerator(KeyStroke.getKeyStroke("control shift C"));
		closeOthViewBtn.setAccelerator(KeyStroke.getKeyStroke("control shift O"));

		gitInitBtn.setAccelerator(KeyStroke.getKeyStroke("control alt I"));
		gitCloneBtn.setAccelerator(KeyStroke.getKeyStroke("control alt O"));
		gitStatusBtn.setAccelerator(KeyStroke.getKeyStroke("control alt S"));
		gitLogBtn.setAccelerator(KeyStroke.getKeyStroke("control alt L"));
		gitAddBtn.setAccelerator(KeyStroke.getKeyStroke("control alt A"));
		gitCommitBtn.setAccelerator(KeyStroke.getKeyStroke("control alt C"));
		gitPushBtn.setAccelerator(KeyStroke.getKeyStroke("control alt U"));
		gitTagBtn.setAccelerator(KeyStroke.getKeyStroke("control alt T"));
		gitDiffBtn.setAccelerator(KeyStroke.getKeyStroke("control alt D"));
		gitFetchBtn.setAccelerator(KeyStroke.getKeyStroke("control alt F"));
		gitPullBtn.setAccelerator(KeyStroke.getKeyStroke("control alt P"));
	}

	public KVimMenuBar(KVimTab tab) {
		this.curTab = tab;

		fillFile();
		fillFileNew();
		this.add(fileBtn);

		fillEdit();
		this.add(editBtn);

		fillView();
		this.add(viewBtn);

		fillProjects();
		this.add(projBtn);

		fillGit();
		this.add(gitBtn);

		fillTools();
		this.add(toolsBtn);

		fillHelp();
		this.add(helpBtn);
	}

	public void fillFile() {
		KVimOpenRecent.getRecentFiles().forEach(recentFile -> {
			if(!Files.exists(recentFile.getPath())) {
				recentFile.setEnabled(false);
			}
			openRecBtn.add(recentFile);
		});
		openBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimOpen.openFile();
			}
		});
		saveBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimSave.openSaveChooser(curTab);
			}
		});
		mvBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimFile.moveFile(curTab);
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		cpBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimFile.copyFile(curTab);
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		deleteBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimFile.deleteFile(curTab);
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		reloadBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimReload.reloadFile(curTab);
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		cpPathBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(curTab.getFilePath().toString()), null);
			}
		});
		openDirBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					Desktop.getDesktop().open(curTab.getFilePath().toFile().getParentFile());
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		propsBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					new KVimProperties(curTab.getFilePath()).setVisible(true);
				} catch(IOException | NoSuchAlgorithmException e) {
					throw new RuntimeException(e);
				}
			}
		});

		restartBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimRestart.askRestart();
				} catch(UnsupportedLookAndFeelException | IOException | InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		});
		quitBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimClose.askExit();
			}
		});

		fileBtn.add(newBtn);
		fileBtn.add(openBtn);
		fileBtn.add(openRecBtn);
		fileBtn.addSeparator();
		fileBtn.add(saveBtn);
		fileBtn.addSeparator();
		fileBtn.add(mvBtn);
		fileBtn.add(cpBtn);
		fileBtn.add(deleteBtn);
		fileBtn.addSeparator();
		fileBtn.add(reloadBtn);
		fileBtn.addSeparator();
		fileBtn.add(cpPathBtn);
		fileBtn.add(openDirBtn);
		fileBtn.add(propsBtn);
		fileBtn.addSeparator();
		fileBtn.add(restartBtn);
		fileBtn.add(quitBtn);

		if(curTab.isUntitled()) {
			mvBtn.setEnabled(false);
			cpBtn.setEnabled(false);
			reloadBtn.setEnabled(false);
			deleteBtn.setEnabled(false);
			cpPathBtn.setEnabled(false);
			openDirBtn.setEnabled(false);
			propsBtn.setEnabled(false);
		}
	}

	public void fillFileNew() {
		untNew.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				createUntitledTab();
			}
		});

		newBtn.add(untNew);
		newBtn.add(txtNew);
		newBtn.add(shNew);
		newBtn.add(javaNew);
		newBtn.add(ktNew);
		newBtn.add(cNew);
		newBtn.add(cppNew);
		newBtn.add(csNew);
		newBtn.add(hNew);
		newBtn.add(pyNew);
		newBtn.add(goNew);
		newBtn.add(rbNew);
		newBtn.add(phpNew);
		newBtn.add(htmlNew);
		newBtn.add(cssNew);
		newBtn.add(jsNew);
		newBtn.add(sqlNew);
	}

	public void fillEdit() {
		selAllBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimSelect.selectAll(curTab);
			}
		});

		deselectBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimSelect.deselectAll(curTab);
			}
		});

		curTab.addKeyListener(new KeyAdapter() {
			@Override
			public void keyTyped(KeyEvent keyEvent) {
				if(isOverwriteModeEnabled) {
					int i = curTab.getCaretPosition();
					String tmp = curTab.getText().substring(0, i)
							+ curTab.getText().substring(i + 1);
					curTab.setText(tmp);
					curTab.setCaretPosition(i);
				}
			}
		});

		ovrModBtn.addItemListener(itemEvent -> isOverwriteModeEnabled = ovrModBtn.isSelected());

		cutBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimSelect.cut(curTab);
			}
		});

		copyBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimSelect.copy(curTab);
			}
		});

		pasteBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimSelect.paste(curTab);
			}
		});

		delLineBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimLines.deleteLine(curTab);
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}
			}
		});

		dupLineBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimLines.duplicateLine(curTab);
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}
			}
		});

		swUpBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimLines.swapUpLine(curTab);
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}
			}
		});

		swDownBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimLines.swapDownLine(curTab);
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}
			}
		});

		findBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimFind.findOccurrences(curTab);
			}
		});
		rplBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimReplace.replaceOccurrences(curTab);
			}
		});

		editBtn.add(selAllBtn);
		editBtn.add(deselectBtn);
		editBtn.add(ovrModBtn);
		editBtn.addSeparator();
		editBtn.add(cutBtn);
		editBtn.add(copyBtn);
		editBtn.add(pasteBtn);
		editBtn.addSeparator();
		editBtn.add(delLineBtn);
		editBtn.add(dupLineBtn);
		editBtn.add(swUpBtn);
		editBtn.add(swDownBtn);
		editBtn.addSeparator();
		editBtn.add(findBtn);
		editBtn.add(rplBtn);
		editBtn.addSeparator();
		editBtn.add(symBtn);
	}

	public void fillView() {
		swSidebarBtn.setSelected(isSideBarEnabled);
		autoRlBtn.setSelected(isAutoReloadEnabled);
		swProjectBar.setSelected(isProjectBarEnabled);

		splVertBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				if(tabs.size() >= 2) {
					kVimMain.updateSplit(curTab.getIndex() == 0 ? 1 :
							curTab.getIndex() - 1, curTab.getIndex(), KVimSplitTab.SplitOrientation.VERTICAL);
				} else {
					createUntitledTab();
					kVimMain.updateSplit(0, 1, KVimSplitTab.SplitOrientation.VERTICAL);
				}
			}
		});

		splHorizBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				if(tabs.size() >= 2) {
					kVimMain.updateSplit(curTab.getIndex() == 0 ? 1 :
							curTab.getIndex() - 1, curTab.getIndex(), KVimSplitTab.SplitOrientation.HORIZONTAL);
				} else {
					createUntitledTab();
					kVimMain.updateSplit(0, 1, KVimSplitTab.SplitOrientation.HORIZONTAL);
				}
			}
		});

		prevTabBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				kVimMain.updateTab(curTab.getIndex() - 1, false);
			}
		});

		nextTabBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				kVimMain.updateTab(curTab.getIndex() + 1, false);
			}
		});

		closeCurViewBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimCloseView.closeCurrentView(curTab);
			}
		});

		closeOthViewBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimCloseView.closeOthersView(curTab);
			}
		});

		gotoLine.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimLines.gotoLine(curTab);
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}
			}
		});

		swSidebarBtn.addItemListener(itemEvent -> {
			isSideBarEnabled = swSidebarBtn.isSelected();
			if(isSplit) {
				kVimMain.updateSplit(
						KVimSplitTab.curLeftTab.getIndex(), KVimSplitTab.curRightTab.getIndex(), KVimSplitTab.curOrientation);
			} else {
				kVimMain.updateTab(curTab.getIndex(), false);
			}
		});

		autoRlBtn.addItemListener(itemEvent -> isAutoReloadEnabled = autoRlBtn.isSelected());

		Arrays.asList(curTab.getListeners(CaretListener.class)).forEach(curTab::removeCaretListener);
		curTab.addCaretListener(listener);

		if(!curTab.isUntitled() && autoRlBtn.isSelected()) {
			TimerTask autoReloadTask = new FileWatcher(curTab.getFilePath().toFile()) {
				@Override
				protected void onChange(File file) throws IOException {
					curTab.setText(KVimOpen.getFileContent(curTab.getFilePath()));
				}
			};
			new Timer().schedule(autoReloadTask, new Date(), 3000);
		}

		viewBtn.add(splVertBtn);
		viewBtn.add(splHorizBtn);
		viewBtn.addSeparator();
		viewBtn.add(prevTabBtn);
		viewBtn.add(nextTabBtn);
		viewBtn.addSeparator();
		viewBtn.add(closeCurViewBtn);
		viewBtn.add(closeOthViewBtn);
		viewBtn.addSeparator();
		viewBtn.add(gotoLine);
		viewBtn.addSeparator();
		viewBtn.add(swSidebarBtn);
		viewBtn.add(autoRlBtn);

		if(curTab.getIndex() == 0) {
			prevTabBtn.setEnabled(false);
		}

		if(curTab.getIndex() == tabs.size() - 1) {
			nextTabBtn.setEnabled(false);
		}
	}

	public void fillProjects() {
		projBtn.add(todoBtn);
		projBtn.add(swProjectBar);

		todoBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {

				try {
					KVimTODO todo = new KVimTODO(curTab);
					todo.setVisible(true);
				} catch(IOException | ClassNotFoundException e) {
					throw new RuntimeException(e);
				}
			}
		});

		swProjectBar.addItemListener(itemEvent -> {
			isProjectBarEnabled = swProjectBar.isSelected();
			kVimMain.updateTab(curTab.getIndex(), false);
		});

		if(!curTab.isProject()) {
			projBtn.setEnabled(false);
		}
	}

	public void fillGit() {
		gitInitBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitInit.initRepo(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitCloneBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitClone.cloneRepo();
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitStatusBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitStatus.showStatus(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitLogBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitLog.showLog(curTab);
				} catch(GitAPIException e) {
					JOptionPane.showMessageDialog(kVimMain,
							"No log available : No HEAD exists and no explicit starting revision was specified",
							"No Log", JOptionPane.WARNING_MESSAGE);
				}
			}
		});

		gitAddBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitAdd.addFile(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});
		gitRmBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitRemoveCached.removeCached(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});
		gitCommitBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitCommit.commit(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitPushBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitPush.pushCommits(curTab);
				} catch(GitAPIException | URISyntaxException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitTagBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitTag.tagCommit(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitDiffBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitDiff.showDiffs(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});
		gitFetchBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitFetch.fetch(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitPullBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitPull.pull(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitBranchAddBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KvimGitBranchAdd.branchAdd(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});

		gitBranchDelBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimGitBranchDelete.branchDelete(curTab);
				} catch(GitAPIException e) {
					throw new RuntimeException(e);
				}
			}
		});


		gitBranchBtn.add(gitBranchAddBtn);
		gitBranchBtn.add(gitBranchDelBtn);

		if(curTab.hasAGitRepo()) {
			try {
				final List<Ref> list = curTab.getGitRepository().branchList().call();

				if(!list.isEmpty()) {
					list.forEach(ref -> {
						final JMenuItem jMenuItem = new JMenuItem(ref.getName());
						jMenuItem.addActionListener(new AbstractAction() {
							@Override
							public void actionPerformed(ActionEvent actionEvent) {
								try {
									curTab.getGitRepository().checkout().setName(ref.getName()).call();
								} catch(GitAPIException e) {
									throw new RuntimeException(e);
								}
							}
						});
						gitCheckoutBtn.add(jMenuItem);
					});
				}
			} catch(GitAPIException e) {
				throw new RuntimeException(e);
			}
		}

		gitBtn.add(gitInitBtn);
		gitBtn.add(gitCloneBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitStatusBtn);
		gitBtn.add(gitLogBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitAddBtn);
		gitBtn.add(gitRmBtn);
		gitBtn.add(gitCommitBtn);
		gitBtn.add(gitPushBtn);
		gitBtn.add(gitTagBtn);
		gitBtn.add(gitDiffBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitFetchBtn);
		gitBtn.add(gitPullBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitBranchBtn);
		gitBtn.add(gitCheckoutBtn);

		if(curTab.isUntitled()) {
			gitBtn.setEnabled(false);
		}

		if(curTab.hasAGitRepo()) {
			gitInitBtn.setEnabled(false);
		} else {
			new ArrayList<>(Arrays.asList(gitStatusBtn, gitLogBtn, gitAddBtn, gitCommitBtn, gitPushBtn, gitTagBtn,
					gitRmBtn, gitDiffBtn, gitFetchBtn, gitMergeBtn, gitPullBtn, gitBranchBtn, gitCheckoutBtn)).forEach(item -> item.setEnabled(false));
		}
	}

	public void fillTools() {
		upText.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.uppercaseSelection(curTab);
			}
		});

		lowText.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.lowercaseSelection(curTab);
			}
		});

		capText.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.capitalizeSelection(curTab);
			}
		});

		googleText.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimTools.googleSelection(curTab);
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			}
		});

		insUUID.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				try {
					KVimTools.genRandomUUID(curTab);
				} catch(BadLocationException e) {
					throw new RuntimeException(e);
				}
			}
		});

		md5.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.getMD5Sum(curTab);
			}
		});

		sha1.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.getSHA1Sum(curTab);
			}
		});

		sha256.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.getSHA256(curTab);
			}
		});

		sha512.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				KVimTools.getSHA512Sum(curTab);
			}
		});

		checksum.add(md5);
		checksum.add(sha1);
		checksum.add(sha256);
		checksum.add(sha512);

		toolsBtn.add(upText);
		toolsBtn.add(lowText);
		toolsBtn.add(capText);
		toolsBtn.addSeparator();
		toolsBtn.add(googleText);
		toolsBtn.addSeparator();
		toolsBtn.add(checksum);
		toolsBtn.addSeparator();
		toolsBtn.add(insUUID);
	}

	public void fillHelp() {

		about.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent actionEvent) {
				new KVimAbout().setVisible(true);
			}
		});

		helpBtn.add(whatsThis);
		helpBtn.add(reportBug);
		helpBtn.add(about);
	}
}
