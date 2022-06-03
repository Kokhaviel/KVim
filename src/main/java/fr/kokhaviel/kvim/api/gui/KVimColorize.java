package fr.kokhaviel.kvim.api.gui;

import fr.kokhaviel.kvim.api.FileType;

import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public class KVimColorize extends KeyAdapter {

	Color keywordColor = new Color(255, 145, 0);

	@Override
	public void keyTyped(KeyEvent keyEvent) {
		if(!(keyEvent.getSource() instanceof KVimTab)) return;

		final KVimTab source = (KVimTab) keyEvent.getSource();

		if(source.getFileType() == null || source.getFileType() == FileType.UNTITLED
				|| source.getFileType() == FileType.OTHER) return;

		source.getFileType().getKeywords().forEach(keyword -> {
			List<Integer> indexes = new ArrayList<>();
			final String txt = source.getText();

			int index = txt.indexOf(keyword);
			indexes.add(index);
			while (index != -1) {
				index = txt.indexOf(keyword, index + 1);
				indexes.add(index);
			}

			StyledDocument styledDocument = source.getStyledDocument();

			final StyleContext cont = StyleContext.getDefaultStyleContext();
			final AttributeSet attr = cont.addAttribute(cont.getEmptySet(), StyleConstants.Foreground, keywordColor);

			indexes.forEach(i ->
			{
				if(i == -1) return;
				styledDocument.setCharacterAttributes(i, keyword.length(), attr, false);
			});
		});
	}
}
