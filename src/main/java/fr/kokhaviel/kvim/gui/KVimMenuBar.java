package fr.kokhaviel.kvim.gui;

import fr.kokhaviel.kvim.api.FileType;
import fr.kokhaviel.kvim.api.actions.file.KVimNewFile;
import fr.kokhaviel.kvim.api.actions.file.KVimOpenRecent;
import fr.kokhaviel.kvim.api.gui.*;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;

public class KVimMenuBar extends JMenuBar {

	KVimTab curTab;

	//TODO : Add GITHUB Menu
	//TODO : Add Icon Images

	JMenu fileBtn  = new JMenu("File");
	JMenu editBtn  = new JMenu("Edit");
	JMenu viewBtn  = new JMenu("View");
	JMenu codeBtn  = new JMenu("Code");
	JMenu gitBtn   = new JMenu("Git");
	JMenu toolsBtn = new JMenu("Tools");
	JMenu projBtn  = new JMenu("Projects");
	JMenu settBtn  = new JMenu("Settings");
	JMenu helpBtn  = new JMenu("Help");

	//File Menu
	JMenu     newBtn        = new KVimMenu    ("New", new ImageIcon(ClassLoader.getSystemResource("menubar/file/new.png")));
	JMenuItem openBtn       = new KVimMenuItem("Open", new ImageIcon(ClassLoader.getSystemResource("menubar/file/open.png")));
	JMenu     openRecBtn    = new KVimMenu    ("Open Recent", new ImageIcon(ClassLoader.getSystemResource("menubar/file/openrecent.png")));
	JMenuItem saveBtn       = new KVimMenuItem("Save", new ImageIcon(ClassLoader.getSystemResource("menubar/file/save.png")));
	JMenuItem mvBtn         = new JMenuItem("Move File");
	JMenuItem cpBtn         = new JMenuItem("Copy File");
	JMenuItem reloadBtn     = new JMenuItem("Reload");
	JMenuItem printBtn      = new JMenuItem("Print");
	JMenuItem renameBtn     = new JMenuItem("Rename");
	JMenuItem deleteBtn     = new JMenuItem("Delete");
	JMenuItem cpPathBtn     = new JMenuItem("Copy File Path");
	JMenuItem openDirBtn    = new JMenuItem("Open Containing Dir");
	JMenuItem propsBtn      = new JMenuItem("File properties");
	JMenuItem restartBtn    = new JMenuItem("Restart KVim");
	JMenuItem quitBtn       = new JMenuItem("Quit KVim");

	//File/New Menu
	JMenuItem untNew  = new JMenuItem      ("Untitled");
	JMenuItem txtNew  = new KVimNewMenuItem("File",            FileType.TEXT);
	JMenuItem javaNew = new KVimNewMenuItem("Java File",       FileType.JAVA);
	JMenuItem ktNew   = new KVimNewMenuItem("Kotlin File",     FileType.KOTLIN);
	JMenuItem phpNew  = new KVimNewMenuItem("PHP File",        FileType.PHP);
	JMenuItem pyNew   = new KVimNewMenuItem("Python File",     FileType.PYTHON);
	JMenuItem htmlNew = new KVimNewMenuItem("HTML File",       FileType.HTML);
	JMenuItem cssNew  = new KVimNewMenuItem("CSS File",        FileType.CSS);
	JMenuItem jsNew   = new KVimNewMenuItem("JavaScript File", FileType.JAVASCRIPT);
	JMenuItem cNew    = new KVimNewMenuItem("C File",          FileType.C);
	JMenuItem cppNew  = new KVimNewMenuItem("C++ File",        FileType.CPP);
	JMenuItem csNew   = new KVimNewMenuItem("C# File",         FileType.CSHARP);
	JMenuItem hNew    = new KVimNewMenuItem("C Header",        FileType.H);
	JMenuItem sqlNew  = new KVimNewMenuItem("SQL File",        FileType.SQL);
	JMenuItem shNew   = new KVimNewMenuItem("Shell Script",    FileType.SHELL);
	JMenuItem goNew   = new KVimNewMenuItem("Go File",         FileType.GO);
	JMenuItem rbNew   = new KVimNewMenuItem("Ruby File",       FileType.RUBY);

	//Edit Menu
	JMenuItem undoBtn  = new JMenuItem("Undo");
	JMenuItem redoBtn  = new JMenuItem("Redo");
	JMenuItem cutBtn   = new JMenuItem("Cut");
	JMenuItem copyBtn  = new JMenuItem("Copy");
	JMenuItem pasteBtn = new JMenuItem("Paste");
	JMenuItem findBtn  = new JMenuItem("Find");
	JMenuItem rplBtn   = new JMenuItem("Replace");
	JMenuItem symBtn   = new JMenuItem("Insert Symbols");

	JMenuItem selAllBtn   = new JMenuItem("Select All");
	JMenuItem deselectBtn = new JMenuItem("Deselect");
	JCheckBox blkSelBtn   = new JCheckBox("Block Selection");
	JCheckBox ovrModBtn   = new JCheckBox("Overwrite Mode");
	JMenuItem delLineBtn  = new JMenuItem("Delete Line");
	JMenuItem dupLineBtn  = new JMenuItem("Duplicate Line");
	JMenuItem swUpAllBtn  = new JMenuItem("Swap Up Line");
	JMenuItem swDownBtn   = new JMenuItem("Swap Down Line");
	JMenuItem comLineBtn  = new JMenuItem("Comment Line");

	//View Menu
	JMenuItem prevTabBtn      = new JMenuItem("Previous Tab");
	JMenuItem nextTabBtn      = new JMenuItem("Next Tab");
	JCheckBox autoRlBtn       = new JCheckBox("Auto Reload Document");
	JMenuItem splVertBtn      = new JMenuItem("Split Vertical");
	JMenuItem splHorizBtn     = new JMenuItem("Split Horizontal");
	JMenuItem closeCurViewBtn = new JMenuItem("Close Current View");
	JMenuItem closeOthViewBtn = new JMenuItem("Close Other Views");
	JCheckBox swSidebarBtn    = new JCheckBox("Show/Hide Sidebar");
	JMenuItem zoomPlus        = new JMenuItem("Zoom +");
	JMenuItem zoomMinus       = new JMenuItem("Zoom -");
	JCheckBox swLineNbs       = new JCheckBox("Show/Hide Line Numbers");
	JMenuItem gotoLine        = new JMenuItem("Goto Line");
	JCheckBox fullScreen      = new JCheckBox("Full Screen Mode");

	//Code Menu
	JMenuItem generateBtn     = new JMenuItem("Generate Code");
	JMenuItem reformatBtn    = new JMenuItem("Reformat Code");

	//Projects Menu
	JCheckBox swProjectBar = new JCheckBox("Show Project Explorer");
	JMenuItem todoBtn      = new JMenuItem("Project TODO list");

	//Git Menu
	JMenuItem gitInitBtn     = new JMenuItem("Git Init");
	JMenuItem gitAddBtn      = new JMenuItem("Git Add");
	JMenuItem gitBranchBtn   = new JMenuItem("Git Branch");
	JMenu     gitCheckoutBtn = new JMenu    ("Git Checkout");
	JMenuItem gitCommitBtn   = new JMenuItem("Git Commit");
	JMenuItem gitDiffBtn     = new JMenuItem("Git Diff");
	JMenuItem gitFetchBtn    = new JMenuItem("Git Fetch");
	JMenuItem gitLogBtn      = new JMenuItem("Git Log");
	JMenuItem gitMergeBtn    = new JMenuItem("Git Merge");
	JMenuItem gitMvBtn       = new JMenuItem("Git Move");
	JMenuItem gitPullBtn     = new JMenuItem("Git Pull");
	JMenuItem gitPushBtn     = new JMenuItem("Git Push");
	JMenuItem gitRebaseBtn   = new JMenuItem("Git Rebase");
	JMenuItem gitResetBtn    = new JMenuItem("Git Reset");
	JMenuItem gitRestoreBtn  = new JMenuItem("Git Restore");
	JMenuItem gitRevertBtn   = new JMenuItem("Git Revert");
	JMenuItem gitRmBtn       = new JMenuItem("Git Remove");
	JMenuItem gitStatusBtn   = new JMenuItem("Git Status");
	JMenuItem gitTagBtn      = new JMenuItem("Git Tag");

	//Tools Menu
	JMenuItem upText     = new JMenuItem("Uppercase Selection");
	JMenuItem lowText    = new JMenuItem("Lowercase Selection");
	JMenuItem capText    = new JMenuItem("Capitalize Selection");
	JMenuItem googleText = new JMenuItem("Google Selection");
	JMenuItem insUUID    = new JMenuItem("Insert Random UUID");
	JMenuItem compareBtn = new JMenuItem("Diff Files");
	JMenu     checksum   = new JMenu    ("Checksum");
	JMenuItem termBtn    = new JMenuItem("Terminal");

	//Settings Menu
	JMenuItem confColors = new JMenuItem("Configure Color Scheme");
	JCheckBox swToolbar  = new JCheckBox("Show/Hide Toolbar");
	JCheckBox swPath     = new JCheckBox("Show/Hide Path in Frame Title");
	JMenuItem confKBMap  = new JMenuItem("Configure Keyboard Map");
	JMenuItem confTb     = new JMenuItem("Configure Toolbar");
	JMenuItem allSett    = new JMenuItem("All Settings");

	//Help Menu
	JMenuItem whatsThis   = new JMenuItem("What's This ?");
	JMenuItem tipOfTheDay = new JMenuItem("Tip of the Day");
	JMenuItem reportBug   = new JMenuItem("Report Bug ...");
	JMenuItem about       = new JMenuItem("About KVim");

	public KVimMenuBar(KVimTab tab) {
		this.curTab = tab;

		fillFile();
		fillFileNew();
		this.add(fileBtn);

		fillEdit();
		this.add(editBtn);

		fillView();
		this.add(viewBtn);

		fillCode();
		this.add(codeBtn);

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
		KVimOpenRecent.getRecentsFiles().forEach(openRecBtn::add);

		fileBtn.add(newBtn);
		fileBtn.add(openBtn);
		fileBtn.add(openRecBtn);
		fileBtn.addSeparator();
		fileBtn.add(saveBtn);
		fileBtn.addSeparator();
		fileBtn.add(mvBtn);
		fileBtn.add(cpBtn);
		fileBtn.addSeparator();
		fileBtn.add(reloadBtn);
		fileBtn.addSeparator();
		fileBtn.add(printBtn);
		fileBtn.addSeparator();
		fileBtn.add(renameBtn);
		fileBtn.add(deleteBtn);
		fileBtn.addSeparator();
		fileBtn.add(cpPathBtn);
		fileBtn.add(openDirBtn);
		fileBtn.add(propsBtn);
		fileBtn.addSeparator();
		fileBtn.add(restartBtn);
		fileBtn.add(quitBtn);

		if(curTab.isUntitled()) {
			saveBtn.setEnabled(false);
			mvBtn.setEnabled(false);
			cpBtn.setEnabled(false);
			reloadBtn.setEnabled(false);
			renameBtn.setEnabled(false);
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
				KVimNewFile.createUntitledTab();
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
		editBtn.add(undoBtn);
		editBtn.add(redoBtn);
		editBtn.addSeparator();
		editBtn.add(selAllBtn);
		editBtn.add(deselectBtn);
		editBtn.add(blkSelBtn);
		editBtn.add(ovrModBtn);
		editBtn.addSeparator();
		editBtn.add(cutBtn);
		editBtn.add(copyBtn);
		editBtn.add(pasteBtn);
		editBtn.addSeparator();
		editBtn.add(delLineBtn);
		editBtn.add(dupLineBtn);
		editBtn.add(swUpAllBtn);
		editBtn.add(swDownBtn);
		editBtn.addSeparator();
		editBtn.add(findBtn);
		editBtn.add(rplBtn);
		editBtn.addSeparator();
		editBtn.add(comLineBtn);
		editBtn.add(symBtn);
	}

	public void fillView() {
		viewBtn.add(splVertBtn);
		viewBtn.add(splHorizBtn);
		viewBtn.addSeparator();
		viewBtn.add(prevTabBtn);
		viewBtn.add(nextTabBtn);
		viewBtn.addSeparator();
		viewBtn.add(closeCurViewBtn);
		viewBtn.add(closeOthViewBtn);
		viewBtn.addSeparator();
		viewBtn.add(zoomPlus);
		viewBtn.add(zoomMinus);
		viewBtn.addSeparator();
		viewBtn.add(gotoLine);
		viewBtn.addSeparator();
		viewBtn.add(swLineNbs);
		viewBtn.add(autoRlBtn);
		viewBtn.add(fullScreen);
		viewBtn.add(swSidebarBtn);
	}

	public void fillCode() {
		codeBtn.add(generateBtn);
		codeBtn.add(reformatBtn);
	}

	public void fillProjects() {
		projBtn.add(todoBtn);
		projBtn.add(swProjectBar);
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
		gitBtn.add(gitCheckoutBtn);	//TODO : GIT CHECKOUT BRANCH MENU
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
		helpBtn.add(tipOfTheDay);
		helpBtn.add(reportBug);
		helpBtn.add(about);
	}
}
