package com.freshorangeslices.enshining.core;

import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;

/** The seven elements. Each companion has one (Lumine will switch); reactions between them come later. */
public enum Element {
	ANEMO(0x74C2A8),
	GEO(0xF0B232),
	ELECTRO(0xAF8EC1),
	DENDRO(0xA5C83B),
	HYDRO(0x4CC2F1),
	PYRO(0xEF7938),
	CRYO(0x9FD6E3);

	private final int color;
	private final DustParticleOptions dust;

	Element(int color) {
		this.color = color;
		this.dust = new DustParticleOptions(new Vector3f(((color >> 16) & 0xFF) / 255F, ((color >> 8) & 0xFF) / 255F, (color & 0xFF) / 255F), 1.2F);
	}

	public int color() {
		return color;
	}

	/** Colored dust in the element's color, for its effects. */
	public DustParticleOptions dust() {
		return dust;
	}
}
