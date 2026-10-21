package net.netzgut.eclipse.tapestry.internal.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

/// Keeps the problem markers of templates up to date, in a background job: a template when it is
/// saved, all templates of a project when its Java code or message catalogs change (a component may
/// have been added or renamed), and all templates when the bundle starts or a severity changes.
///
/// Only templates in source folders of Java projects using Tapestry are validated, so copies in
/// output folders (`target/classes`) get no markers.
public final class TemplateValidation
    implements
        IResourceChangeListener,
        IEclipsePreferences.IPreferenceChangeListener {

    public static final String MARKER = "net.netzgut.eclipse.tapestry.templateProblem";

    /// Marker attribute naming the [TemplateValidator.Check].
    public static final String CHECK = "check";

    public static final TemplateValidation INSTANCE = new TemplateValidation();

    /// Waits for further changes, e.g. while a refactoring touches many files.
    private static final long DELAY_MS = 500;

    private final Set<IFile>    pendingFiles    = new LinkedHashSet<>();
    private final Set<IProject> pendingProjects = new LinkedHashSet<>();
    private final Job           job             = Job.create("Validating Tapestry templates", this::run);

    private TemplateValidation() {
        this.job.setSystem(true);
        this.job.setPriority(Job.DECORATE);
    }

    public void start() {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        workspace.addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
        InstanceScope.INSTANCE.getNode(TapestryPreferences.QUALIFIER).addPreferenceChangeListener(this);
        validateAll();
    }

    public void stop() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        InstanceScope.INSTANCE.getNode(TapestryPreferences.QUALIFIER).removePreferenceChangeListener(this);
        this.job.cancel();
    }

    /// Revalidates the templates of all open projects.
    public void validateAll() {
        synchronized (this) {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen()) {
                    this.pendingProjects.add(project);
                }
            }
        }

        this.job.schedule(DELAY_MS);
    }

    @Override
    public void preferenceChange(IEclipsePreferences.PreferenceChangeEvent event) {
        if (event.getKey().startsWith(TapestryPreferences.VALIDATION_PREFIX)) {
            validateAll();
        }
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }

        Set<IFile> files = new LinkedHashSet<>();
        Set<IProject> projects = new LinkedHashSet<>();
        try {
            delta.accept(child -> {
                IResource resource = child.getResource();
                if (resource instanceof IProject project) {
                    // Opened, or a new project: all of it
                    if ((child.getFlags() & IResourceDelta.OPEN) != 0 || child.getKind() == IResourceDelta.ADDED) {
                        if (project.isOpen()) {
                            projects.add(project);
                        }

                        return false;
                    }

                    return true;
                }

                if (!(resource instanceof IFile file)) {
                    return true;
                }

                boolean changed = child.getKind() == IResourceDelta.ADDED
                                  || child.getKind() == IResourceDelta.REMOVED
                                  || (child.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) != 0;
                if (!changed) {
                    return false; // only markers changed, e.g. ours
                }

                if (RelatedFiles.isTemplate(file)) {
                    if (child.getKind() != IResourceDelta.REMOVED) {
                        files.add(file);
                    }
                }
                else if (isJavaOrCatalog(file)) {
                    projects.add(file.getProject());
                }

                return false;
            });
        }
        catch (CoreException e) {
            ILog.of(TemplateValidation.class).error("Collecting changed templates failed", e);
        }

        if (files.isEmpty() && projects.isEmpty()) {
            return;
        }

        synchronized (this) {
            this.pendingFiles.addAll(files);
            this.pendingProjects.addAll(projects);
        }

        this.job.schedule(DELAY_MS);
    }

    private static boolean isJavaOrCatalog(IFile file) {
        String extension = file.getFileExtension();
        return "java".equals(extension)
               || "class".equals(extension)
               || "jar".equals(extension)
               || MessageCatalogs.isCatalog(file)
               || ".classpath".equals(file.getName());
    }

    private IStatus run(IProgressMonitor monitor) {
        List<IFile> files;
        List<IProject> projects;

        synchronized (this) {
            projects = new ArrayList<>(this.pendingProjects);
            this.pendingProjects.clear();
            files = new ArrayList<>(this.pendingFiles);
            this.pendingFiles.clear();
        }

        Map<IProject, Set<IFile>> templates = new LinkedHashMap<>();
        for (IProject project : projects) {
            List<IFile> projectTemplates = templatesOf(project);
            templates.computeIfAbsent(project, _ -> new LinkedHashSet<>()) //
                     .addAll(projectTemplates);
        }

        for (IFile file : files) {
            templates.computeIfAbsent(file.getProject(), _ -> new LinkedHashSet<>()) //
                     .add(file);
        }

        int templateCount = templates.values() //
                                     .stream() //
                                     .mapToInt(Set::size) //
                                     .sum();
        SubMonitor progress = SubMonitor.convert(monitor, templateCount);
        Map<TemplateValidator.Check, Integer> severities = severities();
        for (Map.Entry<IProject, Set<IFile>> entry : templates.entrySet()) {
            if (progress.isCanceled()) {
                return Status.CANCEL_STATUS;
            }

            validate(entry.getKey(), entry.getValue(), severities, progress);
        }

        return Status.OK_STATUS;
    }

    private static void validate(IProject project,
                                 Set<IFile> templates,
                                 Map<TemplateValidator.Check, Integer> severities,
                                 SubMonitor progress) {
        try {
            if (!project.isOpen()) {
                return;
            }

            IJavaProject javaProject = JavaCore.create(project);
            boolean active = !severities.isEmpty() && TemplateValidator.usesTapestry(javaProject);
            TemplateValidator validator;
            if (active) {
                validator = new TemplateValidator(javaProject, severities.keySet(), progress);
            }
            else {
                validator = null;
            }

            Set<IContainer> sourceFolders;
            if (javaProject.exists()) {
                List<IContainer> folders = RelatedFiles.sourceFolders(javaProject);
                sourceFolders = new LinkedHashSet<>(folders);
            }
            else {
                sourceFolders = Set.of();
            }

            for (IFile template : templates) {
                if (progress.isCanceled()) {
                    return;
                }

                if (template.exists()) {
                    boolean inSource =
                        sourceFolders.stream()
                                     .anyMatch(folder -> folder.getFullPath().isPrefixOf(template.getFullPath()));
                    List<TemplateValidator.Problem> problems;
                    if (validator != null && inSource) {
                        String content = TemplateLocator.read(template);
                        problems = validator.validate(template, content);
                    }
                    else {
                        problems = List.of();
                    }

                    update(template, problems, severities);
                }

                progress.worked(1);
            }
        }
        catch (CoreException e) {
            if (project.isOpen()) {
                ILog.of(TemplateValidation.class).error("Validating templates of " + project.getName() + " failed", e);
            }
        }
    }

    /// Replaces the template's markers, unless they are the same already.
    private static void update(IFile template,
                               List<TemplateValidator.Problem> problems,
                               Map<TemplateValidator.Check, Integer> severities) throws CoreException {
        IMarker[] existing = template.findMarkers(MARKER, false, IResource.DEPTH_ZERO);
        if (existing.length == 0 && problems.isEmpty()) {
            return;
        }

        if (same(existing, problems, severities)) {
            return;
        }

        String content;
        if (problems.isEmpty()) {
            content = "";
        }
        else {
            content = TemplateLocator.read(template);
        }

        IWorkspace workspace = template.getWorkspace();
        ISchedulingRule markerRule = workspace.getRuleFactory().markerRule(template);
        workspace.run(_ -> {
            template.deleteMarkers(MARKER, false, IResource.DEPTH_ZERO);
            for (TemplateValidator.Problem problem : problems) {
                Integer severity = severities.get(problem.check());
                int lineNumber = line(content, problem.offset());
                Map<String, Object> attributes = Map.of(IMarker.MESSAGE,
                                                        problem.message(),
                                                        IMarker.SEVERITY,
                                                        severity,
                                                        IMarker.CHAR_START,
                                                        problem.offset(),
                                                        IMarker.CHAR_END,
                                                        problem.offset() + problem.length(),
                                                        IMarker.LINE_NUMBER,
                                                        lineNumber,
                                                        CHECK,
                                                        problem.check().name());
                template.createMarker(MARKER, attributes);
            }
        }, markerRule, IWorkspace.AVOID_UPDATE, null);
    }

    private static boolean same(IMarker[] markers,
                                List<TemplateValidator.Problem> problems,
                                Map<TemplateValidator.Check, Integer> severities) throws CoreException {
        if (markers.length != problems.size()) {
            return false;
        }

        Set<List<Object>> expected = new HashSet<>();
        for (TemplateValidator.Problem problem : problems) {
            expected.add(List.of(problem.message(), severities.get(problem.check()), problem.offset()));
        }

        for (IMarker marker : markers) {
            String message = String.valueOf(marker.getAttribute(IMarker.MESSAGE));
            List<Object> actual = List.of(message,
                                          marker.getAttribute(IMarker.SEVERITY, -1),
                                          marker.getAttribute(IMarker.CHAR_START, -1));
            if (!expected.contains(actual)) {
                return false;
            }
        }

        return true;
    }

    private static int line(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }

        return line;
    }

    /// The enabled checks and their marker severities.
    private static Map<TemplateValidator.Check, Integer> severities() {
        Map<TemplateValidator.Check, Integer> severities = new EnumMap<>(TemplateValidator.Check.class);
        for (TemplateValidator.Check check : EnumSet.allOf(TemplateValidator.Check.class)) {
            switch (TapestryPreferences.severity(check.preference)) {
                case TapestryPreferences.ERROR -> severities.put(check, IMarker.SEVERITY_ERROR);
                case TapestryPreferences.WARNING -> severities.put(check, IMarker.SEVERITY_WARNING);
            }
        }

        return severities;
    }

    private static List<IFile> templatesOf(IProject project) {
        List<IFile> templates = new ArrayList<>();
        try {
            if (!project.isOpen()) {
                return templates;
            }

            project.accept((IResourceVisitor) resource -> {
                if (resource instanceof IFile file) {
                    if (RelatedFiles.isTemplate(file)) {
                        templates.add(file);
                    }

                    return false;
                }

                return !resource.isDerived() && !resource.isTeamPrivateMember();
            }, IResource.DEPTH_INFINITE, IResource.NONE);
        }
        catch (CoreException e) {
            ILog.of(TemplateValidation.class).error("Listing templates of " + project.getName() + " failed", e);
        }

        return templates;
    }
}
