package fr.kokhaviel.kvim.gui;

import fr.kokhaviel.kvim.api.FileType;
import fr.kokhaviel.kvim.api.actions.FileWatcher;
import fr.kokhaviel.kvim.api.actions.edit.*;
import fr.kokhaviel.kvim.api.actions.file.*;
import fr.kokhaviel.kvim.api.actions.todos.KVimTODO;
import fr.kokhaviel.kvim.api.gui.*;
import fr.kokhaviel.kvim.gui.split.KVimSplitTab;

import javax.swing.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Timer;

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
	JMenu settBtn = new JMenu("Settings");
	JMenu helpBtn = new JMenu("Help");

	//File Menu
	JMenu newBtn = new KVimMenu("New", new ImageIcon(ClassLoader.getSystemResource("menubar/file/new.png")));
	JMenuItem openBtn = new KVimMenuItem("Open", new ImageIcon(ClassLoader.getSystemResource("menubar/file/open.png")));
	JMenu openRecBtn = new KVimMenu("Open Recent", new ImageIcon(ClassLoader.getSystemResource("menubar/file/openrecent.png")));
	JMenuItem saveBtn = new KVimMenuItem("Save", new ImageIcon(ClassLoader.getSystemResource("menubar/file/save.png")));
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
	JMenuItem gitAddBtn = new JMenuItem("Git Add");
	JMenuItem gitBranchBtn = new JMenuItem("Git Branch");
	JMenu gitCheckoutBtn = new JMenu("Git Checkout");
	JMenuItem gitCommitBtn = new JMenuItem("Git Commit");
	JMenuItem gitDiffBtn = new JMenuItem("Git Diff");
	JMenuItem gitFetchBtn = new JMenuItem("Git Fetch");
	JMenuItem gitLogBtn = new JMenuItem("Git Log");
	JMenuItem gitMergeBtn = new JMenuItem("Git Merge");
	JMenuItem gitMvBtn = new JMenuItem("Git Move");
	JMenuItem gitPullBtn = new JMenuItem("Git Pull");
	JMenuItem gitPushBtn = new JMenuItem("Git Push");
	JMenuItem gitRebaseBtn = new JMenuItem("Git Rebase");
	JMenuItem gitResetBtn = new JMenuItem("Git Reset");
	JMenuItem gitRestoreBtn = new JMenuItem("Git Restore");
	JMenuItem gitRevertBtn = new JMenuItem("Git Revert");
	JMenuItem gitRmBtn = new JMenuItem("Git Remove");
	JMenuItem gitStatusBtn = new JMenuItem("Git Status");
	JMenuItem gitTagBtn = new JMenuItem("Git Tag");

	//Tools Menu
	JMenuItem upText = new JMenuItem("Uppercase Selection");
	JMenuItem lowText = new JMenuItem("Lowercase Selection");
	JMenuItem capText = new JMenuItem("Capitalize Selection");
	JMenuItem googleText = new JMenuItem("Google Selection");
	JMenuItem insUUID = new JMenuItem("Insert Random UUID");
	JMenuItem compareBtn = new JMenuItem("Diff Files");
	JMenu checksum = new JMenu("Checksum");
	JMenuItem termBtn = new JMenuItem("Terminal");

	//Settings Menu
	JMenuItem confColors = new JMenuItem("Configure Color Scheme");
	JCheckBox swToolbar = new JCheckBox("Show/Hide Toolbar");
	JCheckBox swPath = new JCheckBox("Show/Hide Path in Frame Title");
	JMenuItem confKBMap = new JMenuItem("Configure Keyboard Map");
	JMenuItem confTb = new JMenuItem("Configure Toolbar");
	JMenuItem allSett = new JMenuItem("All Settings");

	//Help Menu
	JMenuItem whatsThis = new JMenuItem("What's This ?");
	JMenuItem reportBug = new JMenuItem("Report Bug ...");
	JMenuItem about = new JMenuItem("About KVim");

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

		fillSettings();
		this.add(settBtn);

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
				if(ovrModBtn.isSelected()) {
					int i = curTab.getCaretPosition();
					String tmp = curTab.getText().substring(0, i)
							+ curTab.getText().substring(i + 1);
					curTab.setText(tmp);
					curTab.setCaretPosition(i);
				}
			}
		});

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
		autoRlBtn.setSelected(isProjectBarEnabled);
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
		gitBtn.add(gitInitBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitStatusBtn);
		gitBtn.add(gitLogBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitAddBtn);
		gitBtn.add(gitCommitBtn);
		gitBtn.add(gitPushBtn);
		gitBtn.add(gitTagBtn);
		gitBtn.add(gitDiffBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitFetchBtn);
		gitBtn.add(gitMergeBtn);
		gitBtn.add(gitPullBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitBranchBtn);
		gitBtn.add(gitCheckoutBtn);    //TODO : GIT CHECKOUT BRANCH MENU
		gitBtn.add(gitRebaseBtn);
		gitBtn.add(gitResetBtn);
		gitBtn.add(gitRestoreBtn);
		gitBtn.add(gitRevertBtn);
		gitBtn.addSeparator();
		gitBtn.add(gitMvBtn);
		gitBtn.add(gitRmBtn);

		if(curTab.isUntitled()) {
			gitBtn.setEnabled(false);
		}

		if(curTab.hasAGitRepo()) {
			gitInitBtn.setEnabled(false);
		} else {
			new ArrayList<>(Arrays.asList(gitStatusBtn, gitLogBtn, gitAddBtn, gitCommitBtn, gitPushBtn, gitTagBtn,
					gitDiffBtn, gitFetchBtn, gitMergeBtn, gitPullBtn, gitBranchBtn, gitCheckoutBtn, gitRebaseBtn,
					gitResetBtn, gitRestoreBtn, gitRevertBtn, gitMvBtn, gitRmBtn)).forEach(item -> item.setEnabled(false));
		}
	}

	public void fillTools() {
		toolsBtn.add(upText);
		toolsBtn.add(lowText);
		toolsBtn.add(capText);
		toolsBtn.addSeparator();
		toolsBtn.add(googleText);
		toolsBtn.addSeparator();
		toolsBtn.add(compareBtn);
		toolsBtn.addSeparator();
		toolsBtn.add(checksum);
		toolsBtn.addSeparator();
		toolsBtn.add(insUUID);
		toolsBtn.addSeparator();
		toolsBtn.add(termBtn);
	}

	public void fillSettings() {
		settBtn.add(allSett);
		settBtn.addSeparator();
		settBtn.add(confColors);
		settBtn.add(confKBMap);
		settBtn.add(confTb);
		settBtn.addSeparator();
		settBtn.add(swPath);
		settBtn.add(swToolbar);
	}

	public void fillHelp() {
		helpBtn.add(whatsThis);
		helpBtn.add(reportBug);
		helpBtn.add(about);
	}
}
