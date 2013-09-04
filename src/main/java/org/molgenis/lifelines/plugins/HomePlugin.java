package org.molgenis.lifelines.plugins;

import org.molgenis.framework.ui.IframePlugin;
import org.molgenis.framework.ui.ScreenController;
import org.molgenis.lifelines.controller.HomeController;

public class HomePlugin extends IframePlugin
{
	private static final long serialVersionUID = 1L;

	public HomePlugin(String name, ScreenController<?> parent)
	{
		super(name, parent);
	}

	@Override
	public String getIframeSrc()
	{
		return HomeController.URI;
	}
}