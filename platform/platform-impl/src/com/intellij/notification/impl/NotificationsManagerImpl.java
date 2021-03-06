// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.notification.impl;

import com.intellij.codeInsight.hint.TooltipController;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.FrameStateListener;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonPainter;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.notification.*;
import com.intellij.notification.impl.ui.NotificationsUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.DialogWrapperDialog;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeBalloonLayoutImpl;
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame;
import com.intellij.ui.*;
import com.intellij.ui.components.GradientViewport;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ArrayUtil;
import com.intellij.util.FontUtil;
import com.intellij.util.Function;
import com.intellij.util.IconUtil;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import com.intellij.util.ui.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.plaf.ButtonUI;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.UIResource;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NotificationsManagerImpl extends NotificationsManager {
  public static final Color DEFAULT_TEXT_COLOR = new JBColor(Gray._0, Gray._191);
  private static final Color TEXT_COLOR = JBColor.namedColor("Notification.foreground", DEFAULT_TEXT_COLOR);
  public static final Color FILL_COLOR = JBColor.namedColor("Notification.background", new JBColor(Gray._242, new Color(78, 80, 82)));
  public static final Color BORDER_COLOR = JBColor.namedColor("Notification.borderColor", new JBColor(Gray._178.withAlpha(205), new Color(86, 90, 92, 205)));

  private @Nullable List<Notification> myEarlyNotifications = new ArrayList<>();

  public NotificationsManagerImpl() {
    ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
      @Override
      public void projectClosed(@NotNull Project project) {
        for (Notification notification : getNotificationsOfType(Notification.class, project)) {
          notification.hideBalloon();
        }
        TooltipController.getInstance().resetCurrent();
      }
    });
  }

  @Override
  public void expire(@NotNull Notification notification) {
    UIUtil.invokeLaterIfNeeded(() -> EventLog.expireNotification(notification));
  }

  public void expireAll() {
    EventLog.expireNotifications();
 }

  @Override
  public <T extends Notification> T @NotNull [] getNotificationsOfType(@NotNull Class<T> klass, @Nullable Project project) {
    final List<T> result = new ArrayList<>();
    if (project == null || !project.isDefault() && !project.isDisposed()) {
      for (Notification notification : EventLog.getLogModel(project).getNotifications()) {
        if (klass.isInstance(notification)) {
          //noinspection unchecked
          result.add((T)notification);
        }
      }
    }
    return ArrayUtil.toObjectArray(result, klass);
  }

  private void doNotify(@NotNull Notification notification, @Nullable Project project) {
    NotificationsConfigurationImpl configuration = NotificationsConfigurationImpl.getInstanceImpl();
    if (!configuration.isRegistered(notification.getGroupId())) {
      configuration.register(notification.getGroupId(), NotificationDisplayType.BALLOON);
    }

    NotificationSettings settings = NotificationsConfigurationImpl.getSettings(notification.getGroupId());
    boolean shouldLog = settings.isShouldLog();
    boolean displayable = settings.getDisplayType() != NotificationDisplayType.NONE;

    boolean willBeShown = displayable && NotificationsConfigurationImpl.getInstanceImpl().SHOW_BALLOONS;
    if (!shouldLog && !willBeShown) {
      notification.expire();
    }

    if (NotificationsConfigurationImpl.getInstanceImpl().SHOW_BALLOONS) {
      if (project == null) {
        GuiUtils.invokeLaterIfNeeded(() -> showNotification(notification, null), ModalityState.any(), ApplicationManager.getApplication().getDisposed());
      }
      else if (!project.isDisposed()) {
        StartupManager.getInstance(project).runAfterOpened(() -> {
          GuiUtils.invokeLaterIfNeeded(() -> showNotification(notification, project), ModalityState.any(), project.getDisposed());
        });
      }
    }
  }

  @RequiresEdt
  @ApiStatus.Internal
  public void dispatchEarlyNotifications() {
    if (myEarlyNotifications != null) {
      List<Notification> copy = myEarlyNotifications;
      myEarlyNotifications = null;
      copy.forEach(early -> showNotification(early, null));
    }
  }

  @RequiresEdt
  private void showNotification(Notification notification, @Nullable Project project) {
    if (myEarlyNotifications != null) {
      myEarlyNotifications.add(notification);
      return;
    }

    String groupId = notification.getGroupId();
    NotificationSettings settings = NotificationsConfigurationImpl.getSettings(groupId);

    NotificationDisplayType type = settings.getDisplayType();
    String toolWindowId = NotificationsConfigurationImpl.getInstanceImpl().getToolWindowId(groupId);
    if (type == NotificationDisplayType.TOOL_WINDOW &&
        (toolWindowId == null || project == null || !ToolWindowManager.getInstance(project).canShowNotification(toolWindowId))) {
      type = NotificationDisplayType.BALLOON;
    }

    switch (type) {
      case NONE:
        return;
      case STICKY_BALLOON:
      case BALLOON:
      default:
        Balloon balloon = notifyByBalloon(notification, type, project);
        if (project == null || project.isDefault()) {
          return;
        }
        if (!settings.isShouldLog() || type == NotificationDisplayType.STICKY_BALLOON) {
          if (balloon == null) {
            notification.expire();
          }
          else {
            balloon.addListener(new JBPopupListener() {
              @Override
              public void onClosed(@NotNull LightweightWindowEvent event) {
                if (!event.isOk()) {
                  notification.expire();
                }
              }
            });
          }
        }
        break;
      case TOOL_WINDOW:
        MessageType messageType = notification.getType() == NotificationType.ERROR
                                  ? MessageType.ERROR
                                  : notification.getType() == NotificationType.WARNING ? MessageType.WARNING : MessageType.INFO;
        List<AnAction> actions = notification.getActions();
        final NotificationListener notificationListener = notification.getListener();
        HyperlinkListener listener = notificationListener == null ? null : new HyperlinkListener() {
          @Override
          public void hyperlinkUpdate(HyperlinkEvent e) {
            notificationListener.hyperlinkUpdate(notification, e);
          }
        };
        String msg = notification.getTitle();
        if (StringUtil.isNotEmpty(notification.getContent())) {
          if (StringUtil.isNotEmpty(msg)) {
            msg += "<br>";
          }
          msg += notification.getContent();
        }

        if (!actions.isEmpty()) {
          msg += HtmlChunk.br();

          for (int index = 0; index < actions.size(); index++) {
            AnAction action = actions.get(index);
            var linkTarget = "notification-action-" + index + "for-tool-window-" + System.identityHashCode(notification);
            String text = action.getTemplatePresentation().getText();
            if (text == null) continue;

            //noinspection StringConcatenationInLoop
            msg += HtmlChunk.link(linkTarget, text) + " ";

            var oldListener = listener;
            listener = new HyperlinkListener() {
              @Override
              public void hyperlinkUpdate(HyperlinkEvent e) {
                if (!e.getDescription().equals(linkTarget)) {
                  oldListener.hyperlinkUpdate(e);
                  return;
                }

                if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;

                Object source = e.getSource();
                DataContext context = source instanceof Component ? DataManager.getInstance().getDataContext((Component)source) : null;

                NotificationCollector.getInstance()
                  .logNotificationActionInvoked(project, notification, action, NotificationCollector.NotificationPlace.TOOL_WINDOW);

                Notification.fire(notification, action, context);
              }
            };
          }
        }

        Window window = findWindowForBalloon(project);
        if (window instanceof IdeFrame) {
          BalloonLayout layout = ((IdeFrame)window).getBalloonLayout();
          if (layout != null) {
            ((BalloonLayoutImpl)layout).remove(notification);
          }
        }

        //noinspection SSBasedInspection
        ToolWindowManager.getInstance(Objects.requireNonNull(project)).notifyByBalloon(toolWindowId, messageType, msg, notification.getIcon(), listener);
        NotificationCollector.getInstance().logToolWindowNotificationShown(project, notification);
    }
  }

  private static @Nullable Balloon notifyByBalloon(@NotNull Notification notification,
                                                   @NotNull NotificationDisplayType displayType,
                                                   @Nullable Project project) {
    if (isDummyEnvironment()) {
      return null;
    }

    Window window = findWindowForBalloon(project);
    if (!(window instanceof IdeFrame)) {
      return null;
    }

    BalloonLayout layout = ((IdeFrame)window).getBalloonLayout();
    if (layout == null) {
      return null;
    }

    Ref<BalloonLayoutData> layoutDataRef = new Ref<>();
    if (project == null || project.isDefault()) {
      BalloonLayoutData layoutData = new BalloonLayoutData();
      layoutData.groupId = "";
      layoutData.welcomeScreen = layout instanceof WelcomeBalloonLayoutImpl;
      layoutData.type = notification.getType();
      layoutDataRef.set(layoutData);
    }
    else {
      BalloonLayoutData.MergeInfo mergeData = ((BalloonLayoutImpl)layout).preMerge(notification);
      if (mergeData != null) {
        BalloonLayoutData layoutData = new BalloonLayoutData();
        layoutData.mergeData = mergeData;
        layoutDataRef.set(layoutData);
      }
    }
    final Balloon balloon = createBalloon((IdeFrame)window, notification, false, false, layoutDataRef,
                                          project != null ? project : ApplicationManager.getApplication());

    if (notification.isExpired()) {
      return null;
    }

    BalloonLayoutData layoutData = layoutDataRef.get();
    layout.add(balloon, layoutData);
    if (balloon.isDisposed()) {
      return null;
    }

    if (layoutData != null) {
      layoutData.project = project;
    }
    ((BalloonImpl)balloon).startFadeoutTimer(0);
    if (displayType == NotificationDisplayType.BALLOON || ProjectUtil.getOpenProjects().length == 0) {
      frameActivateBalloonListener(balloon, () -> {
        if (!balloon.isDisposed()) {
          ((BalloonImpl)balloon).startSmartFadeoutTimer(10000);
        }
      });
    }
    NotificationCollector.getInstance().logBalloonShown(project, displayType, notification, layoutData != null && layoutData.isExpandable);
    return balloon;
  }

  public static void frameActivateBalloonListener(@NotNull Disposable parentDisposable, @NotNull Runnable callback) {
    if (ApplicationManager.getApplication().isActive()) {
      callback.run();
    }
    else {
      Disposable listenerDisposable = Disposer.newDisposable();
      Disposer.register(parentDisposable, listenerDisposable);
      ApplicationManager.getApplication().getMessageBus().connect(parentDisposable).subscribe(FrameStateListener.TOPIC, new FrameStateListener() {
        @Override
        public void onFrameActivated() {
          Disposer.dispose(listenerDisposable);
          callback.run();
        }
      });
    }
  }

  public static @Nullable Window findWindowForBalloon(@Nullable Project project) {
    Window frame = WindowManager.getInstance().getFrame(project);
    if (frame == null && project == null) {
      frame = (Window)WelcomeFrame.getInstance();
    }
    if (frame == null && project == null) {
      frame = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
      while (frame instanceof DialogWrapperDialog) {
        DialogWrapper wrapper = ((DialogWrapperDialog)frame).getDialogWrapper();
        if (wrapper == null || !wrapper.isModalProgress()) break;
        frame = frame.getOwner();
      }
    }
    if (frame == null && project == null) {
      frame = WindowManager.getInstance().findVisibleFrame();
    }
    return frame;
  }

  public static @NotNull Balloon createBalloon(@NotNull IdeFrame window,
                                               @NotNull Notification notification,
                                               boolean showCallout,
                                               boolean hideOnClickOutside,
                                               @NotNull Ref<BalloonLayoutData> layoutDataRef,
                                               @NotNull Disposable parentDisposable) {
    return createBalloon(window.getComponent(), notification, showCallout, hideOnClickOutside, layoutDataRef, parentDisposable);
  }

  public static @NotNull Balloon createBalloon(@Nullable JComponent windowComponent,
                                               @NotNull Notification notification,
                                               boolean showCallout,
                                               boolean hideOnClickOutside,
                                               @NotNull Ref<BalloonLayoutData> layoutDataRef,
                                               @NotNull Disposable parentDisposable) {
    final BalloonLayoutData layoutData = layoutDataRef.isNull() ? new BalloonLayoutData() : layoutDataRef.get();
    if (layoutData.groupId == null) {
      layoutData.groupId = notification.getGroupId();
    }
    else {
      layoutData.groupId = null;
      layoutData.mergeData = null;
    }
    layoutData.id = notification.id;
    layoutData.displayId = notification.displayId;
    layoutDataRef.set(layoutData);

    if (layoutData.textColor == null) {
      layoutData.textColor = TEXT_COLOR;
    }
    if (layoutData.fillColor == null) {
      layoutData.fillColor = FILL_COLOR;
    }
    if (layoutData.borderColor == null) {
      layoutData.borderColor = BORDER_COLOR;
    }

    boolean actions = !notification.getActions().isEmpty() || notification.getContextHelpAction() != null;
    boolean showFullContent = layoutData.showFullContent || notification instanceof NotificationFullContent;

    final JEditorPane text = new JEditorPane() {
      @Override
      protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (layoutData.showMinSize) {
          Point location = getCollapsedTextEndLocation(this, layoutData);
          if (location != null) {
            if (g instanceof Graphics2D) {
              ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            g.setColor(getForeground());
            g.drawString("...", location.x, location.y + g.getFontMetrics().getAscent());
          }
        }
      }
    };
    HTMLEditorKit kit = new UIUtil.JBWordWrapHtmlEditorKit();
    kit.getStyleSheet().addRule("a {color: " + ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED) + "}");
    text.setEditorKit(kit);
    text.setForeground(layoutData.textColor);

    final HyperlinkListener listener = NotificationsUtil.wrapListener(notification);
    if (listener != null) {
      text.addHyperlinkListener(listener);
    }

    String fontStyle = NotificationsUtil.getFontStyle();
    int prefSize = new JLabel(NotificationsUtil.buildHtml(notification, null, true, null, fontStyle)).getPreferredSize().width;
    String style = prefSize > BalloonLayoutConfiguration.MaxWidth() ? BalloonLayoutConfiguration.MaxWidthStyle() : null;

    if (layoutData.showFullContent) {
      style = prefSize > BalloonLayoutConfiguration.MaxFullContentWidth() ? BalloonLayoutConfiguration.MaxFullContentWidthStyle() : null;
    }

    String textContent = NotificationsUtil.buildHtml(notification, style, true, null, fontStyle);
    text.setText(textContent);
    text.setEditable(false);
    text.setOpaque(false);

    text.setBorder(null);

    final JPanel content = new NonOpaquePanel(new BorderLayout());

    if (text.getCaret() != null) {
      text.setCaretPosition(0);
    }

    final JScrollPane pane = createBalloonScrollPane(text, false);

    pane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
      @Override
      public void adjustmentValueChanged(AdjustmentEvent e) {
        JScrollBar scrollBar = pane.getVerticalScrollBar();
        if (layoutData.showMinSize && scrollBar.getValue() > 0) {
          scrollBar.removeAdjustmentListener(this);
          scrollBar.setValue(0);
          scrollBar.addAdjustmentListener(this);
        }
      }
    });

    LinkLabel<Void> expandAction = null;

    int lines = 3;
    if (notification.hasTitle()) {
      lines--;
    }
    if (actions) {
      lines--;
    }

    layoutData.fullHeight = text.getPreferredSize().height;
    layoutData.twoLineHeight = calculateContentHeight(lines);
    layoutData.maxScrollHeight = Math.min(layoutData.fullHeight, calculateContentHeight(10));
    layoutData.configuration = BalloonLayoutConfiguration.create(notification, layoutData, actions);

    if (layoutData.welcomeScreen) {
      layoutData.maxScrollHeight = layoutData.fullHeight;
    }
    else if (!showFullContent && layoutData.maxScrollHeight != layoutData.fullHeight) {
      pane.setViewport(new GradientViewport(text, JBInsets.create(10, 0), true) {
        @Override
        protected @Nullable Color getViewColor() {
          return layoutData.fillColor;
        }

        @Override
        protected void paintGradient(Graphics g) {
          if (!layoutData.showMinSize) {
            super.paintGradient(g);
          }
        }
      });
    }

    configureBalloonScrollPane(pane, layoutData.fillColor);

    if (showFullContent) {
      if (windowComponent == null) {
        pane.setPreferredSize(text.getPreferredSize());
      }
      else {
        pane.setPreferredSize(
          new Dimension(text.getPreferredSize().width, (int)Math.min(layoutData.fullHeight, windowComponent.getHeight() * 0.75)));
      }
    }
    else if (layoutData.twoLineHeight < layoutData.fullHeight) {
      text.setPreferredSize(null);
      Dimension size = text.getPreferredSize();
      size.height = layoutData.twoLineHeight;
      text.setPreferredSize(size);
      text.setSize(size);
      layoutData.showMinSize = true;

      pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
      pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
      pane.setPreferredSize(size);

      text.setCaret(new TextCaret(layoutData));

      expandAction = new LinkLabel<>(null, AllIcons.Ide.Notification.Expand, new LinkListener<>() {
        @Override
        public void linkSelected(LinkLabel<Void> link, Void ignored) {
          layoutData.showMinSize = !layoutData.showMinSize;

          text.setPreferredSize(null);
          Dimension size = text.getPreferredSize();

          if (layoutData.showMinSize) {
            size.height = layoutData.twoLineHeight;
            pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
            link.setIcon(AllIcons.Ide.Notification.Expand);
            link.setHoveringIcon(AllIcons.Ide.Notification.ExpandHover);
            NotificationCollector.getInstance().logNotificationBalloonCollapsed(layoutData.project, notification);
          }
          else {
            text.select(0, 0);
            size.height = layoutData.fullHeight;
            pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            link.setIcon(AllIcons.Ide.Notification.Collapse);
            link.setHoveringIcon(AllIcons.Ide.Notification.CollapseHover);
            NotificationCollector.getInstance().logNotificationBalloonExpanded(layoutData.project, notification);
          }

          text.setPreferredSize(size);
          text.setSize(size);

          if (!layoutData.showMinSize) {
            size = new Dimension(size.width, layoutData.maxScrollHeight);
          }
          pane.setPreferredSize(size);

          content.doLayout();
          layoutData.doLayout.run();
        }
      });
      expandAction.setHoveringIcon(AllIcons.Ide.Notification.ExpandHover);
      layoutData.isExpandable = true;
    }

    NotificationCenterPanel centerPanel = new NotificationCenterPanel(text, layoutData);
    content.add(centerPanel, BorderLayout.CENTER);

    if (notification.hasTitle()) {
      String titleStyle = StringUtil.defaultIfEmpty(fontStyle, "") + "white-space:nowrap;";
      JLabel title = new JLabel();
      title.setText(NotificationsUtil.buildHtml(notification, titleStyle, false, null, null));
      title.setOpaque(false);
      title.setForeground(layoutData.textColor);
      centerPanel.addTitle(title);
    }

    if (expandAction != null) {
      centerPanel.addExpandAction(expandAction);
    }

    if (notification.hasContent()) {
      centerPanel.addContent(layoutData.welcomeScreen ? text : pane);
    }

    if (!layoutData.welcomeScreen) {
      final Icon icon = NotificationsUtil.getIcon(notification);
      JComponent iconComponent = new JComponent() {
        @Override
        protected void paintComponent(Graphics g) {
          super.paintComponent(g);
          icon.paintIcon(this, g, layoutData.configuration.iconOffset.width, layoutData.configuration.iconOffset.height);
        }
      };
      iconComponent.setOpaque(false);
      iconComponent.setPreferredSize(
        new Dimension(layoutData.configuration.iconPanelWidth, 2 * layoutData.configuration.iconOffset.height + icon.getIconHeight()));

      content.add(iconComponent, BorderLayout.WEST);
    }

    JPanel buttons = createButtons(notification, content, listener);
    if (buttons != null) {
      layoutData.groupId = null;
      layoutData.mergeData = null;
      buttons.setBorder(JBUI.Borders.empty(0, 0, 5, 7));
    }

    HoverAdapter hoverAdapter = new HoverAdapter();
    hoverAdapter.addSource(content);
    hoverAdapter.addSource(centerPanel);
    hoverAdapter.addSource(text);
    hoverAdapter.addSource(pane);

    if (buttons == null && actions) {
      createActionPanel(notification, centerPanel, layoutData.configuration.actionGap, hoverAdapter);
    }

    if (expandAction != null) {
      hoverAdapter.addComponent(expandAction, component -> {
        Rectangle bounds;
        Point location = SwingUtilities.convertPoint(content.getParent(), content.getLocation(), component.getParent());
        if (layoutData.showMinSize) {
          Component centerComponent = layoutData.welcomeScreen ? text : pane;
          Point centerLocation =
            SwingUtilities.convertPoint(centerComponent.getParent(), centerComponent.getLocation(), component.getParent());
          bounds = new Rectangle(location.x, centerLocation.y, content.getWidth(), centerComponent.getHeight());
        }
        else {
          bounds = new Rectangle(location.x, component.getY(), content.getWidth(), component.getHeight());
          JBInsets.addTo(bounds, JBUI.insets(5, 0, 7, 0));
        }
        return bounds;
      });
    }

    hoverAdapter.initListeners();

    if (layoutData.mergeData != null) {
      createMergeAction(layoutData, content);
    }

    text.setSize(text.getPreferredSize());

    Dimension paneSize = new Dimension(text.getPreferredSize());
    int maxWidth = JBUIScale.scale(600);
    if (windowComponent != null) {
      maxWidth = Math.min(maxWidth, windowComponent.getWidth() - 20);
    }
    if (paneSize.width > maxWidth) {
      pane.setPreferredSize(new Dimension(maxWidth, paneSize.height + UIUtil.getScrollBarWidth()));
    }

    final BalloonBuilder builder = JBPopupFactory.getInstance().createBalloonBuilder(content);
    builder.setFillColor(layoutData.fillColor)
      .setCloseButtonEnabled(buttons == null)
      .setShowCallout(showCallout)
      .setShadow(false)
      .setAnimationCycle(200)
      .setHideOnClickOutside(hideOnClickOutside)
      .setHideOnAction(hideOnClickOutside)
      .setHideOnKeyOutside(hideOnClickOutside)
      .setHideOnFrameResize(false)
      .setBorderColor(layoutData.borderColor)
      .setBorderInsets(JBUI.emptyInsets());

    if (layoutData.fadeoutTime != 0) {
      builder.setFadeoutTime(layoutData.fadeoutTime);
    }

    final BalloonImpl balloon = (BalloonImpl)builder.createBalloon();
    balloon.getContent().addMouseListener(new MouseAdapter() {
    });
    balloon.setAnimationEnabled(false);
    notification.setBalloon(balloon);

    balloon.setShadowBorderProvider(new NotificationBalloonShadowBorderProvider(layoutData.fillColor, layoutData.borderColor));

    if (!layoutData.welcomeScreen && buttons == null) {
      balloon.setActionProvider(
        new NotificationBalloonActionProvider(balloon, centerPanel.getTitle(), layoutData, notification.getGroupId(), notification.id, notification.displayId));
    }

    ApplicationManager.getApplication().getMessageBus().connect(balloon).subscribe(LafManagerListener.TOPIC, source -> {
      HTMLEditorKit newKit = new UIUtil.JBWordWrapHtmlEditorKit();
      newKit.getStyleSheet().addRule("a {color: " + ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED) + "}");
      text.setEditorKit(newKit);
      text.setText(textContent);
      text.revalidate();
      text.repaint();
    });

    Disposer.register(parentDisposable, balloon);
    return balloon;
  }

  private static @Nullable JPanel createButtons(@NotNull Notification notification,
                                                @NotNull JPanel content,
                                                @Nullable HyperlinkListener listener) {
    if (notification instanceof NotificationActionProvider) {
      JPanel buttons = new JPanel(new HorizontalLayout(5));
      buttons.setOpaque(false);
      content.add(BorderLayout.SOUTH, buttons);

      final Ref<JButton> defaultButton = new Ref<>();

      NotificationActionProvider provider = (NotificationActionProvider)notification;
      for (NotificationActionProvider.Action action : provider.getActions(listener)) {
        JButton button = new JButton(action) {
          @Override
          public void setUI(ButtonUI ui) {
            boolean isDarcula = ui instanceof DarculaButtonUI && StartupUiUtil.isUnderDarcula();
            if (isDarcula) {
              ui = new DarculaButtonUI() {
                @Override
                protected Color getButtonColorStart() {
                  return new ColorUIResource(0x5a5f61);
                }

                @Override
                protected Color getButtonColorEnd() {
                  return new ColorUIResource(0x5a5f61);
                }
              };
            }
            super.setUI(ui);
            if (isDarcula) {
              setBorder(new DarculaButtonPainter() {
                @Override
                public Paint getBorderPaint(Component button) {
                  return new ColorUIResource(0x717777);
                }
              });
            }
          }
        };

        button.setOpaque(false);
        if (action.isDefaultAction()) {
          defaultButton.setIfNull(button);
        }

        buttons.add(HorizontalLayout.RIGHT, button);
      }

      if (!defaultButton.isNull()) {
        UIUtil.addParentChangeListener(content, new PropertyChangeListener() {
          @Override
          public void propertyChange(PropertyChangeEvent event) {
            if (event.getOldValue() == null && event.getNewValue() != null) {
              UIUtil.removeParentChangeListener(content, this);
              JRootPane rootPane = UIUtil.getRootPane(content);
              if (rootPane != null) {
                rootPane.setDefaultButton(defaultButton.get());
              }
            }
          }
        });
      }

      return buttons;
    }
    return null;
  }

  public static @NotNull JScrollPane createBalloonScrollPane(@NotNull Component content, boolean configure) {
    JScrollPane pane = ScrollPaneFactory.createScrollPane(content, true);
    if (configure) {
      configureBalloonScrollPane(pane, FILL_COLOR);
    }
    return pane;
  }

  public static void configureBalloonScrollPane(@NotNull JScrollPane pane, @NotNull Color fillColor) {
    pane.setOpaque(false);
    pane.getViewport().setOpaque(false);
    pane.setBackground(fillColor);
    pane.getViewport().setBackground(fillColor);
    pane.getVerticalScrollBar().setBackground(fillColor);
  }

  private static void createActionPanel(@NotNull Notification notification,
                                        @NotNull NotificationCenterPanel centerPanel,
                                        int gap,
                                        @NotNull HoverAdapter hoverAdapter) {
    NotificationActionPanel actionPanel = new NotificationActionPanel(gap, notification.getCollapseActionsDirection());
    centerPanel.addActionPanel(actionPanel);

    List<AnAction> actions = notification.getActions();

    if (actions.size() > 2 && notification.getCollapseActionsDirection() == Notification.CollapseActionsDirection.KEEP_RIGHTMOST) {
      addDropDownAction(notification, actionPanel);
    }

    for (AnAction action : actions) {
      Presentation presentation = action.getTemplatePresentation();
      actionPanel.addActionLink(
        new LinkLabel<>(presentation.getText(), presentation.getIcon(), new LinkListener<>() {
          @Override
          public void linkSelected(LinkLabel<AnAction> aSource, AnAction action) {
            NotificationCollector.getInstance()
              .logNotificationActionInvoked(null, notification, action, NotificationCollector.NotificationPlace.BALLOON);
            Notification.fire(notification, action, DataManager.getInstance().getDataContext(aSource));
          }
        }, action));
    }

    if (actions.size() > 2 && notification.getCollapseActionsDirection() == Notification.CollapseActionsDirection.KEEP_LEFTMOST) {
      addDropDownAction(notification, actionPanel);
    }

    AnAction helpAction = notification.getContextHelpAction();
    if (helpAction != null) {
      Presentation presentation = helpAction.getTemplatePresentation();
      ContextHelpLabel helpLabel = new ContextHelpLabel(presentation.getText(), presentation.getDescription());
      helpLabel.setForeground(UIUtil.getLabelDisabledForeground());
      actionPanel.addContextHelpLabel(helpLabel);
    }

    Insets hover = JBUI.insets(8, 5, 8, 7);
    int count = actionPanel.getComponentCount();

    for (int i = 0; i < count; i++) {
      hoverAdapter.addComponent(actionPanel.getComponent(i), hover);
    }

    hoverAdapter.addSource(actionPanel);
  }

  private static void addDropDownAction(@NotNull Notification notification,
                                        NotificationActionPanel actionPanel) {
    DropDownAction action = new DropDownAction(notification.getDropDownText(), new LinkListener<>() {
      @Override
      public void linkSelected(LinkLabel<Void> link, Void ignored) {
        NotificationActionPanel parent = (NotificationActionPanel)link.getParent();
        DefaultActionGroup group = new DefaultActionGroup();
        for (LinkLabel<AnAction> actionLink : parent.actionLinks) {
          if (!actionLink.isVisible()) {
            group.add(actionLink.getLinkData());
          }
        }
        showPopup(link, group);
      }
    });
    Notification.setDataProvider(notification, action);
    action.setVisible(false);
    actionPanel.addGroupedActionsLink(action);
  }

  private static final class HoverAdapter extends MouseAdapter implements MouseMotionListener {
    private final List<Pair<Component, ?>> myComponents = new ArrayList<>();
    private List<Component> mySources = new ArrayList<>();

    private Component myLastComponent;

    public void addComponent(@NotNull Component component, @NotNull Function<? super Component, ? extends Rectangle> hover) {
      myComponents.add(Pair.create(component, hover));
    }

    public void addComponent(@NotNull Component component, @NotNull Insets hover) {
      myComponents.add(Pair.create(component, hover));
    }

    public void addSource(@NotNull Component component) {
      mySources.add(component);
    }

    public void initListeners() {
      if (!myComponents.isEmpty()) {
        for (Component source : mySources) {
          source.addMouseMotionListener(this);
          source.addMouseListener(this);
        }
        mySources = null;
      }
    }

    @Override
    public void mousePressed(MouseEvent e) {
      handleEvent(e, true, false);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
      handleEvent(e, false, false);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
      handleEvent(e, false, true);
    }

    @Override
    public void mouseExited(MouseEvent e) {
      if (myLastComponent != null) {
        mouseExited(e, myLastComponent);
        myLastComponent = null;
      }
    }

    private void handleEvent(MouseEvent e, boolean pressed, boolean moved) {
      if (e.getSource() instanceof JEditorPane) {
        JEditorPane pane = (JEditorPane)e.getSource();
        int pos = pane.viewToModel(e.getPoint());
        if (pos >= 0) {
          HTMLDocument document = (HTMLDocument)pane.getDocument();
          AttributeSet attributes = document.getCharacterElement(pos).getAttributes();
          if (attributes.getAttribute(HTML.Tag.A) != null) {
            return;
          }
        }
      }

      for (Pair<Component, ?> p : myComponents) {
        Component component = p.first;
        Rectangle bounds;
        if (p.second instanceof Insets) {
          bounds = component.getBounds();
          JBInsets.addTo(bounds, (Insets)p.second);
        }
        else {
          //noinspection unchecked
          bounds = ((Function<Component, Rectangle>)p.second).fun(component);
        }
        if (bounds.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), component.getParent()))) {
          if (myLastComponent != null && myLastComponent != component) {
            mouseExited(e, myLastComponent);
          }
          myLastComponent = component;

          MouseEvent event = createEvent(e, component);
          if (moved) {
            for (MouseMotionListener listener : component.getMouseMotionListeners()) {
              listener.mouseMoved(event);
            }
          }
          else {
            MouseListener[] listeners = component.getMouseListeners();
            if (pressed) {
              for (MouseListener listener : listeners) {
                listener.mousePressed(event);
              }
            }
            else {
              for (MouseListener listener : listeners) {
                listener.mouseReleased(event);
              }
            }
          }

          e.getComponent().setCursor(component.getCursor());
          return;
        }
        else if (component == myLastComponent) {
          myLastComponent = null;
          mouseExited(e, component);
        }
      }
    }

    private static void mouseExited(MouseEvent e, Component component) {
      e.getComponent().setCursor(null);

      MouseEvent event = createEvent(e, component);
      MouseListener[] listeners = component.getMouseListeners();
      for (MouseListener listener : listeners) {
        listener.mouseExited(event);
      }
    }

    private static @NotNull MouseEvent createEvent(MouseEvent e, Component c) {
      return new MouseEvent(c, e.getID(), e.getWhen(), e.getModifiers(), 5, 5, e.getClickCount(), e.isPopupTrigger(), e.getButton());
    }
  }

  private static void createMergeAction(@NotNull BalloonLayoutData layoutData, @NotNull JPanel panel) {
    @Nls StringBuilder title = new StringBuilder().
      append(layoutData.mergeData.count).
      append(" ").
      append(IdeBundle.message("notification.manager.merge.more"));

    String shortTitle = NotificationParentGroup.getShortTitle(layoutData.groupId);
    if (shortTitle != null) {
      title.append(" ").append(IdeBundle.message("notification.manager.merge.from")).append(" ").append(shortTitle);
    }

    LinkLabel<BalloonLayoutData> action = new LinkLabel<>(
      title.toString(), null,
      new LinkListener<BalloonLayoutData>() {
        @Override
        public void linkSelected(LinkLabel<BalloonLayoutData> aSource, BalloonLayoutData layoutData) {
          EventLog.showNotification(layoutData.project, layoutData.groupId, layoutData.getMergeIds());
        }
      }, layoutData) {
      @Override
      protected boolean isInClickableArea(Point pt) {
        return true;
      }

      @Override
      protected Color getTextColor() {
        return JBColor.namedColor("Notification.MoreButton.foreground", new JBColor(0x666666, 0x8C8C8C));
      }
    };

    action.setFont(FontUtil.minusOne(action.getFont()));
    action.setHorizontalAlignment(SwingConstants.CENTER);
    action.setPaintUnderline(false);

    AbstractLayoutManager layout = new AbstractLayoutManager() {
      @Override
      public Dimension preferredLayoutSize(Container parent) {
        return new Dimension(parent.getWidth(), JBUIScale.scale(20) + 2);
      }

      @Override
      public void layoutContainer(Container parent) {
        parent.getComponent(0).setBounds(2, 1, parent.getWidth() - 4, JBUIScale.scale(20));
      }
    };
    JPanel mergePanel = new NonOpaquePanel(layout) {
      @Override
      protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.setColor(JBColor.namedColor("Notification.MoreButton.background", new JBColor(0xE3E3E3, 0x3A3C3D)));
        ((Graphics2D)g).fill(new Rectangle2D.Double(1.5, 1, getWidth() - 2.5, getHeight() - 2));
        g.setColor(JBColor.namedColor("Notification.MoreButton.innerBorderColor", new JBColor(0xDBDBDB, 0x353738)));
        if (SystemInfo.isMac) {
          ((Graphics2D)g).draw(new Rectangle2D.Double(2, 0, getWidth() - 3.5, 0.5));
        }
        else if (SystemInfo.isWindows) {
          ((Graphics2D)g).draw(new Rectangle2D.Double(1.5, 0, getWidth() - 3, 0.5));
        }
        else {
          ((Graphics2D)g).draw(new Rectangle2D.Double(1.5, 0, getWidth() - 2.5, 0.5));
        }
      }
    };
    mergePanel.add(action);
    panel.add(BorderLayout.SOUTH, mergePanel);
  }

  public static int calculateContentHeight(int lines) {
    String word = IdeBundle.message("notification.manager.content.height.word");
    String lineBreak = IdeBundle.message("notification.manager.content.height.linebreak");
    String content = word + StringUtil.repeat(lineBreak + word, lines - 1);

    JEditorPane text = new JEditorPane();
    text.setEditorKit(UIUtil.getHTMLEditorKit());
    text.setText(NotificationsUtil.buildHtml(null, null, content, null, null, null, NotificationsUtil.getFontStyle()));
    text.setEditable(false);
    text.setOpaque(false);
    text.setBorder(null);

    return text.getPreferredSize().height;
  }

  private static boolean isDummyEnvironment() {
    Application app = ApplicationManager.getApplication();
    return app.isUnitTestMode() || app.isCommandLine();
  }

  static final class ProjectNotificationsComponent {
    ProjectNotificationsComponent(@NotNull Project project) {
      if (isDummyEnvironment()) {
        return;
      }
      project.getMessageBus().connect().subscribe(Notifications.TOPIC, new MyNotificationListener(project));
    }
  }

  private static class DropDownAction extends LinkLabel<Void> {
    Icon myIcon = AllIcons.Ide.Notification.DropTriangle;

    DropDownAction(@NlsContexts.LinkLabel String text, @Nullable LinkListener<Void> listener) {
      super(text, null, listener);

      setHorizontalTextPosition(SwingConstants.LEADING);
      setIconTextGap(0);

      setIcon(new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
          IconUtil.colorize((Graphics2D)g, myIcon, getTextColor()).paintIcon(c, g, x - 1, y + 1);
        }

        @Override
        public int getIconWidth() {
          return myIcon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
          return myIcon.getIconHeight();
        }
      });
    }

    @Override
    protected @NotNull Rectangle getTextBounds() {
      Rectangle bounds = super.getTextBounds();
      bounds.x -= getIcon().getIconWidth();
      bounds.width += 8;
      return bounds;
    }
  }

  private static void showPopup(@NotNull LinkLabel<?> link, @NotNull DefaultActionGroup group) {
    if (link.isShowing()) {
      ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.UNKNOWN, group);
      menu.getComponent().show(link, JBUIScale.scale(-10), link.getHeight() + JBUIScale.scale(2));
    }
  }

  static final class MyNotificationListener implements Notifications {
    private final Project myProject;

    @SuppressWarnings("unused")
    MyNotificationListener() {
      myProject = null;
    }

    private MyNotificationListener(@Nullable Project project) {
      myProject = project;
    }

    @Override
    public void notify(@NotNull Notification notification) {
      ((NotificationsManagerImpl)NotificationsManager.getNotificationsManager()).doNotify(notification, myProject);
    }
  }

  private static @Nullable Point getCollapsedTextEndLocation(@NotNull JEditorPane text, @NotNull BalloonLayoutData layoutData) {
    try {
      int end = text.viewToModel(new Point(10, layoutData.twoLineHeight + 5));
      if (end == -1) {
        end = text.getDocument().getLength();
      }
      for (int i = end - 1; i >= 0; i--) {
        Rectangle r = text.modelToView(i);
        if (r != null && r.y < layoutData.twoLineHeight) {
          return r.getLocation();
        }
      }
    }
    catch (BadLocationException ignored) {
    }

    return null;
  }

  private static int getFirstLineHeight(@NotNull JEditorPane text) {
    try {
      int end = text.getDocument().getLength();
      for (int i = 0; i < end; i++) {
        Rectangle r = text.modelToView(i);
        if (r != null && r.height > 0) {
          return r.height;
        }
      }
    }
    catch (BadLocationException ignored) {
    }
    return 0;
  }

  private static final class NotificationCenterPanel extends NonOpaquePanel {
    private final CenteredLayoutWithActions myLayout;
    private final BalloonLayoutData myLayoutData;

    private NotificationCenterPanel(JEditorPane text, BalloonLayoutData layoutData) {
      super(new CenteredLayoutWithActions(text, layoutData));
      myLayout = (CenteredLayoutWithActions) getLayout();
      myLayoutData = layoutData;
    }

    public void addTitle(JLabel title) {
      add(title, BorderLayout.NORTH);
      myLayout.myTitleComponent = title;
    }

    public Component getTitle() {
      return myLayout.getTitle();
    }

    public void addExpandAction(LinkLabel<Void> action) {
      add(action, BorderLayout.EAST);
      myLayout.myExpandAction = action;
    }

    public void addContent(JComponent component) {
      add(component, BorderLayout.CENTER);
      myLayout.myCenteredComponent = component;
    }

    public void addActionPanel(NotificationActionPanel panel) {
      add(panel, BorderLayout.SOUTH);
      myLayout.myActionPanel = panel;
    }

    @Override
    protected void paintChildren(Graphics g) {
      super.paintChildren(g);
      Component title = myLayout.getTitle();

      if (title != null && myLayoutData.showActions != null && myLayoutData.showActions.compute()) {
        int width = myLayoutData.configuration.allActionsOffset;
        int x = getWidth() - width - JBUIScale.scale(5);
        int y = myLayoutData.configuration.topSpaceHeight;

        int height = title instanceof JEditorPane ? getFirstLineHeight((JEditorPane)title) : title.getHeight();

        g.setColor(myLayoutData.fillColor);
        g.fillRect(x, y, width, height);

        width = myLayoutData.configuration.beforeGearSpace;
        x -= width;
        ((Graphics2D)g)
          .setPaint(new GradientPaint(x, y, ColorUtil.withAlpha(myLayoutData.fillColor, 0.2), x + width, y, myLayoutData.fillColor));
        g.fillRect(x, y, width, height);
      }
    }
  }

  private static final class NotificationActionPanel extends NonOpaquePanel {
    private final List<LinkLabel<AnAction>> actionLinks = new ArrayList<>();
    private final Notification.CollapseActionsDirection collapseActionsDirection;
    private DropDownAction groupedActionsLink;

    private NotificationActionPanel(int gap, Notification.CollapseActionsDirection direction) {
      super(new HorizontalLayout(gap, SwingConstants.CENTER));
      collapseActionsDirection = direction;
    }

    public void addGroupedActionsLink(DropDownAction action) {
      add(action);
      groupedActionsLink = action;
    }

    public void addActionLink(LinkLabel<AnAction> label) {
      add(HorizontalLayout.LEFT, label);
      actionLinks.add(label);
    }

    public void addContextHelpLabel(ContextHelpLabel label) {
      add(HorizontalLayout.LEFT, label);
    }
  }

  private static class CenteredLayoutWithActions extends BorderLayout {
    private final JEditorPane myText;
    private final BalloonLayoutData myLayoutData;
    private JLabel myTitleComponent;
    private Component myCenteredComponent;
    private NotificationActionPanel myActionPanel;
    private Component myExpandAction;

    CenteredLayoutWithActions(JEditorPane text, BalloonLayoutData layoutData) {
      myText = text;
      myLayoutData = layoutData;
    }

    public @Nullable Component getTitle() {
      if (myTitleComponent != null) {
        return myTitleComponent;
      }
      if (myCenteredComponent != null) {
        if (myCenteredComponent instanceof JScrollPane) {
          return ((JScrollPane)myCenteredComponent).getViewport().getView();
        }
        return myCenteredComponent;
      }
      return null;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      return layoutSize(component -> component.getPreferredSize());
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      return layoutSize(component -> component.getMinimumSize());
    }

    private Dimension layoutSize(@NotNull Function<? super Component, ? extends Dimension> size) {
      Dimension titleSize = myTitleComponent == null ? new Dimension() : size.fun(myTitleComponent);
      Dimension centeredSize = myCenteredComponent == null ? new Dimension() : size.fun(myCenteredComponent);
      Dimension actionSize = myActionPanel == null ? new Dimension() : size.fun(myActionPanel);
      Dimension expandSize = myExpandAction == null || myLayoutData.showMinSize ? new Dimension() : size.fun(myExpandAction);

      int height = myLayoutData.configuration.topSpaceHeight +
                   titleSize.height + centeredSize.height + Math.max(actionSize.height, expandSize.height) +
                   myLayoutData.configuration.bottomSpaceHeight;

      if (titleSize.height > 0 && centeredSize.height > 0) {
        height += myLayoutData.configuration.titleContentSpaceHeight;
      }
      if (centeredSize.height > 0 && actionSize.height > 0) {
        height += myLayoutData.configuration.contentActionsSpaceHeight;
      }
      if (titleSize.height > 0 && actionSize.height > 0) {
        height += myLayoutData.configuration.titleActionsSpaceHeight;
      }

      int titleWidth = titleSize.width + myLayoutData.configuration.closeOffset;
      int centerWidth = centeredSize.width + myLayoutData.configuration.closeOffset;
      int actionWidth = actionSize.width + expandSize.width;

      int width = Math.max(centerWidth, Math.max(titleWidth, actionWidth));
      if (!myLayoutData.showFullContent) {
        width = Math.min(width, BalloonLayoutConfiguration.MaxWidth());
      }
      width = Math.max(width, BalloonLayoutConfiguration.MinWidth());

      return new Dimension(width, height);
    }

    @Override
    public void layoutContainer(Container parent) {
      int top = myLayoutData.configuration.topSpaceHeight;
      int width = parent.getWidth();
      Dimension centeredSize = myCenteredComponent == null ? new Dimension() : myCenteredComponent.getPreferredSize();
      boolean isActions = myActionPanel != null || (myExpandAction != null && !myLayoutData.showMinSize);

      if (myTitleComponent != null) {
        int titleHeight = myTitleComponent.getPreferredSize().height;
        myTitleComponent.setBounds(0, top, width - myLayoutData.configuration.closeOffset, titleHeight);
        top += titleHeight;

        if (myCenteredComponent != null) {
          top += myLayoutData.configuration.titleContentSpaceHeight;
        }
        else if (isActions) {
          top += myLayoutData.configuration.titleActionsSpaceHeight;
        }
      }

      if (myCenteredComponent != null) {
        int centeredWidth = width;
        if (!myLayoutData.showFullContent && !myLayoutData.showMinSize && myLayoutData.fullHeight != myLayoutData.maxScrollHeight) {
          centeredWidth--;
        }
        myCenteredComponent.setBounds(0, top, centeredWidth, centeredSize.height);
        myCenteredComponent.revalidate();
      }

      if (myExpandAction != null) {
        Dimension size = myExpandAction.getPreferredSize();
        int x = width - size.width - Objects.requireNonNull(myLayoutData.configuration.rightActionsOffset).width;

        if (myLayoutData.showMinSize) {
          Point location = getCollapsedTextEndLocation(myText, myLayoutData);
          if (location != null) {
            int y = SwingUtilities.convertPoint(myText, location.x, location.y, parent).y;
            myExpandAction.setBounds(x, y, size.width, size.height);
          }
        }
        else {
          int y = parent.getHeight() - size.height - myLayoutData.configuration.bottomSpaceHeight;
          myExpandAction.setBounds(x, y, size.width, size.height);
        }
      }

      if (myActionPanel != null) {
        int expandWidth = myExpandAction == null || myLayoutData.showMinSize ? 0 : myExpandAction.getPreferredSize().width;
        width -= myLayoutData.configuration.actionGap + expandWidth;

        if (myActionPanel.actionLinks.size() > 2) {
          myActionPanel.groupedActionsLink.setVisible(false);
          for (LinkLabel<AnAction> link : myActionPanel.actionLinks) {
            link.setVisible(true);
          }
          myActionPanel.doLayout();

          boolean keepRightmost = myActionPanel.collapseActionsDirection == Notification.CollapseActionsDirection.KEEP_RIGHTMOST;
          int collapseStart = keepRightmost ? 0 : myActionPanel.actionLinks.size() - 1;
          int collapseDelta = keepRightmost ? 1 : -1;
          int collapseIndex = collapseStart;
          if (myActionPanel.getPreferredSize().width > width) {
            myActionPanel.groupedActionsLink.setVisible(true);
            myActionPanel.actionLinks.get(collapseIndex).setVisible(false);
            collapseIndex += collapseDelta;
            myActionPanel.actionLinks.get(collapseIndex).setVisible(false);
            collapseIndex += collapseDelta;
            myActionPanel.doLayout();
            while (myActionPanel.getPreferredSize().width > width && collapseIndex >= 0 && collapseIndex < myActionPanel.actionLinks.size()) {
              myActionPanel.actionLinks.get(collapseIndex).setVisible(false);
              collapseIndex += collapseDelta;
              myActionPanel.doLayout();
            }
          }
        }

        Dimension size = myActionPanel.getPreferredSize();
        int y = parent.getHeight() - size.height - myLayoutData.configuration.bottomSpaceHeight;
        myActionPanel.setBounds(0, y, width, size.height);
      }
    }
  }

  private static class TextCaret extends DefaultCaret implements UIResource {
    private final BalloonLayoutData myLayoutData;

    TextCaret(@NotNull BalloonLayoutData layoutData) {
      myLayoutData = layoutData;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mouseClicked(e);
      }
    }

    @Override
    public void mousePressed(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mousePressed(e);
      }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mouseReleased(e);
      }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mouseEntered(e);
      }
    }

    @Override
    public void mouseExited(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mouseExited(e);
      }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mouseDragged(e);
      }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
      if (!myLayoutData.showMinSize) {
        super.mouseMoved(e);
      }
    }
  }
}
