/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich.quadmesh;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * Holds a leaf or node of a Quad tree — a rectangle recursively split into
 * 4 (or 2, at an odd width/height edge) children until {@code shapeCriterion}
 * is met. Ported from infimg's {@code quadmesh} package (file-copy
 * convention, see {@link ImageSource}'s doc) and trimmed down to what this
 * project's CLI-only denoiser path actually uses: the GUI-oriented
 * {@code drawMet}/{@code fillMet}/{@code getMet}/{@code merge}/
 * {@code findWidest}/{@code findHighest}/{@code findBiggest} methods and the
 * {@code MeshShape} base class they came from were dropped rather than
 * ported unused. Implements {@link Shape} directly (delegating to
 * {@link #rect}) instead of extending a base class, since nothing here
 * needs {@code MeshShape}'s own userObject/merge machinery.
 */
public class Quad implements Shape {

    /**
     * Extra information set by some ShapeCriteria when the condition is
     * met (here always the leaf's mean Lab, a {@code double[3]} — see
     * {@link DeltaECrit}), else null for an internal (non-leaf) node.
     */
    public Object userObject;

    /**
     * Parent of this Quad, null if root node.
     */
    public final Quad parent;
    /**
     * Area described by this Quad.
     */
    public final Rectangle rect;
    /**
     * Sub-quads if not a leaf node, null otherwise.
     */
    public Quad[] nodes;

    private Quad(Quad parent, ImageSource bi, Rectangle area, ShapeCriterion shapeCriterion) {
        this.parent = parent;
        this.rect = area;
        userObject = shapeCriterion.criterionMet(bi, rect);
        if (null == userObject) {
            if (rect.width > 1) {
                if (rect.height > 1) {
                    int mw = rect.width / 2;
                    int mh = rect.height / 2;
                    nodes = new Quad[4];
                    nodes[0] = new Quad(this, bi, new Rectangle(rect.x, rect.y, mw, mh), shapeCriterion);
                    nodes[1] = new Quad(this, bi, new Rectangle(rect.x + mw, rect.y, rect.width - mw, mh), shapeCriterion);
                    nodes[2] = new Quad(this, bi, new Rectangle(rect.x, rect.y + mh, mw, rect.height - mh), shapeCriterion);
                    nodes[3] = new Quad(this, bi, new Rectangle(rect.x + mw, rect.y + mh, rect.width - mw, rect.height - mh), shapeCriterion);
                } else {
                    int mw = rect.width / 2;
                    nodes = new Quad[2];
                    nodes[0] = new Quad(this, bi, new Rectangle(rect.x, rect.y, mw, rect.height), shapeCriterion);
                    nodes[1] = new Quad(this, bi, new Rectangle(rect.x + mw, rect.y, rect.width - mw, rect.height), shapeCriterion);
                }
            } else if (rect.height > 1) {
                int mh = rect.height / 2;
                nodes = new Quad[2];
                nodes[0] = new Quad(this, bi, new Rectangle(rect.x, rect.y, rect.width, mh), shapeCriterion);
                nodes[1] = new Quad(this, bi, new Rectangle(rect.x, rect.y + mh, rect.width, rect.height - mh), shapeCriterion);
            } else {
                nodes = null;
            }
        } else {
            nodes = null;
        }
    }

    /**
     * Parse the image into Quad leaves using the passed criterion.
     *
     * @param bi Image to examine.
     * @param shapeCriterion Criterion to use.
     */
    public Quad(ImageSource bi, ShapeCriterion shapeCriterion) {
        this(null, bi, new Rectangle(bi.getWidth(), bi.getHeight()), shapeCriterion);
    }

    /**
     * Traverses the Quad tree and applies the QuadProcessor on each leaf.
     *
     * @param parm QuadProcessor to use.
     * @return True if ok to continue, false otherwise. Top-level false means
     * the traversal was aborted.
     */
    public boolean traverse(QuadProcessor parm) {
        if (nodes == null) {
            return parm.process(this);
        }
        for (Quad node : nodes) {
            if (!node.traverse(parm)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean contains(double d, double d1) {
        return rect.contains(d, d1);
    }

    @Override
    public boolean contains(Point2D pd) {
        return rect.contains(pd);
    }

    @Override
    public boolean contains(double d, double d1, double d2, double d3) {
        return rect.contains(d, d1, d2, d3);
    }

    @Override
    public boolean contains(Rectangle2D rd) {
        return rect.contains(rd);
    }

    @Override
    public Rectangle getBounds() {
        return rect.getBounds();
    }

    @Override
    public Rectangle2D getBounds2D() {
        return rect.getBounds2D();
    }

    @Override
    public boolean intersects(double d, double d1, double d2, double d3) {
        return rect.intersects(d, d1, d2, d3);
    }

    @Override
    public boolean intersects(Rectangle2D rd) {
        return rect.intersects(rd);
    }

    @Override
    public PathIterator getPathIterator(AffineTransform at) {
        return rect.getPathIterator(at);
    }

    @Override
    public PathIterator getPathIterator(AffineTransform at, double d) {
        return rect.getPathIterator(at, d);
    }

    @Override
    public String toString() {
        return "Quad{area=" + rect + ", userObject=" + userObject + ", isLeaf=" + (nodes == null) + '}';
    }
}
