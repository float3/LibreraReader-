package com.foobnix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.foobnix.hypen.HypenUtils;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.BookCSS;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestBionicProcess {

    private boolean bionic;
    private boolean hyphens;
    private String lang;

    @Before
    public void keep() {
        bionic = AppState.get().isBionicMode;
        hyphens = BookCSS.get().isAutoHypens;
        lang = AppSP.get().hypenLang;
    }

    @After
    public void restore() {
        AppState.get().isBionicMode = bionic;
        BookCSS.get().isAutoHypens = hyphens;
        AppSP.get().hypenLang = lang;
    }

    // Bionic reading must apply with hyphenation off: it needs no language
    @Test
    public void bionicWithoutHyphens() {
        AppState.get().isBionicMode = true;
        BookCSS.get().isAutoHypens = false;
        AppSP.get().hypenLang = null;
        assertTrue(HypenUtils.isWordsProcess());
    }

    @Test
    public void hyphensNeedLanguage() {
        AppState.get().isBionicMode = false;
        BookCSS.get().isAutoHypens = true;
        AppSP.get().hypenLang = null;
        assertFalse(HypenUtils.isWordsProcess());

        AppSP.get().hypenLang = "en";
        assertTrue(HypenUtils.isWordsProcess());
    }
}
